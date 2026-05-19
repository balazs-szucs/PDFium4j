#include "pdfium4j_shim.h"

// PDFium headers
#include <fpdfview.h>
#include <fpdf_edit.h>
#include <fpdf_flatten.h>
#include <fpdf_formfill.h>
#include <fpdf_annot.h>
#include <fpdf_save.h>

// QPDF headers
#include <qpdf/QPDF.hh>
#include <qpdf/QPDFWriter.hh>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <qpdf/QPDFPageObjectHelper.hh>

// std + compression
#include <vector>
#include <cstring>
#include <stdexcept>
#include <algorithm>
#include <cmath>
#include <sstream>
#include <cstdlib>

#ifdef _WIN32
#include <malloc.h>
#endif

#ifdef PDFIUM4J_HAS_JPEG
#include <turbojpeg.h>
#endif

#ifdef PDFIUM4J_HAS_PNG
#include <png.h>
#include <zlib.h>
#endif

static_assert(sizeof(pdfium4j_flatten_config_t) == 112,
              "Java StructLayout and C struct are out of sync");
static_assert(offsetof(pdfium4j_flatten_config_t, tileSizeBytes) == 80,
              "tileSizeBytes alignment mismatch with Java StructLayout");

// PDFium write callback

struct PDFiumWriteCtx {
    FPDF_FILEWRITE iface;     // must be first
    std::vector<uint8_t>& out;

    PDFiumWriteCtx(std::vector<uint8_t>& o) : out(o) {
        iface.version = 1;
        iface.WriteBlock = [](FPDF_FILEWRITE* self, const void* buf, unsigned long sz) -> int {
            auto* ctx = reinterpret_cast<PDFiumWriteCtx*>(self);
            const auto* bytes = static_cast<const uint8_t*>(buf);
            ctx->out.insert(ctx->out.end(), bytes, bytes + sz);
            return 1;
        };
    }
};

// QPDF custom pipelines

class VectorPipeline final : public Pipeline {
    std::vector<uint8_t>& out_;
public:
    explicit VectorPipeline(std::vector<uint8_t>& out)
        : Pipeline("vector", nullptr), out_(out) {}

    void write(unsigned char const* buf, size_t len) override {
        out_.insert(out_.end(), buf, buf + len);
    }
    void finish() override {}
};

class BorrowedStreamData final : public QPDFObjectHandle::StreamDataProvider {
    const std::vector<uint8_t>& data_;
public:
    explicit BorrowedStreamData(const std::vector<uint8_t>& d) : data_(d) {}
    void provideStreamData(QPDFObjGen const&, Pipeline* pipeline) override {
        pipeline->write(const_cast<unsigned char*>(data_.data()), data_.size());
        pipeline->finish();
    }
};

// JPEG encoding (libjpeg-turbo)

static std::vector<uint8_t> encodeJPEG(
    const uint8_t* bgra, int w, int h, int stride,
    int quality, bool grayscale)
{
#ifdef PDFIUM4J_HAS_JPEG
    tjhandle tj = tjInitCompress();
    if (!tj) throw std::runtime_error("tjInitCompress failed");

    unsigned long jpegSize = 0;
    unsigned char* jpegBuf = nullptr;

    int pixFmt = grayscale ? TJPF_GRAY : TJPF_BGRA;
    int jpegSubsamp = grayscale ? TJSAMP_GRAY : TJSAMP_420;

    int rc = tjCompress2(tj,
        const_cast<uint8_t*>(bgra), w, stride, h,
        pixFmt, &jpegBuf, &jpegSize,
        jpegSubsamp, quality, TJFLAG_FASTDCT);

    if (rc != 0) {
        tjDestroy(tj);
        throw std::runtime_error(std::string("JPEG encode: ") + tjGetErrorStr2(tj));
    }

    std::vector<uint8_t> result(jpegBuf, jpegBuf + jpegSize);
    tjFree(jpegBuf);
    tjDestroy(tj);
    return result;
#else
    throw std::runtime_error("JPEG support not compiled in");
#endif
}

// PNG encoding (libpng)

static std::vector<uint8_t> encodePNG(
    const uint8_t* bgra, int w, int h, int stride,
    int compressionLevel, bool grayscale, bool alpha)
{
#ifdef PDFIUM4J_HAS_PNG
    std::vector<uint8_t> out;
    png_structp png = png_create_write_struct(PNG_LIBPNG_VER_STRING, nullptr, nullptr, nullptr);
    png_infop info  = png_create_info_struct(png);

    if (setjmp(png_jmpbuf(png))) {
        png_destroy_write_struct(&png, &info);
        throw std::runtime_error("libpng encode failed");
    }

    png_set_write_fn(png, &out,
        [](png_structp p, png_bytep data, png_size_t len) {
            auto* v = static_cast<std::vector<uint8_t>*>(png_get_io_ptr(p));
            v->insert(v->end(), data, data + len);
        },
        [](png_structp) {});

    png_set_compression_level(png, compressionLevel);

    int colorType;
    if (grayscale)      colorType = PNG_COLOR_TYPE_GRAY;
    else if (alpha)     colorType = PNG_COLOR_TYPE_RGBA;
    else                colorType = PNG_COLOR_TYPE_RGB;

    png_set_IHDR(png, info, w, h, 8, colorType,
        PNG_INTERLACE_NONE,
        PNG_COMPRESSION_TYPE_DEFAULT,
        PNG_FILTER_TYPE_DEFAULT);
    png_write_info(png, info);

    std::vector<uint8_t> rowBuf(w * (grayscale ? 1 : (alpha ? 4 : 3)));
    for (int y = 0; y < h; ++y) {
        const uint8_t* src = bgra + y * stride;
        if (grayscale) {
            for (int x = 0; x < w; ++x)
                rowBuf[x] = (uint8_t)(src[x*4+0]*0.114f +
                                      src[x*4+1]*0.587f +
                                      src[x*4+2]*0.299f);
        } else if (alpha) {
            for (int x = 0; x < w; ++x) {
                rowBuf[x*4+0] = src[x*4+2]; // R
                rowBuf[x*4+1] = src[x*4+1]; // G
                rowBuf[x*4+2] = src[x*4+0]; // B
                rowBuf[x*4+3] = src[x*4+3]; // A
            }
        } else {
            for (int x = 0; x < w; ++x) {
                rowBuf[x*3+0] = src[x*4+2]; // R
                rowBuf[x*3+1] = src[x*4+1]; // G
                rowBuf[x*3+2] = src[x*4+0]; // B
            }
        }
        png_write_row(png, rowBuf.data());
    }
    png_write_end(png, info);
    png_destroy_write_struct(&png, &info);
    return out;
#else
    throw std::runtime_error("PNG support not compiled in");
#endif
}

// FLATE (raw + deflate)

static std::vector<uint8_t> encodeFLATE(
    const uint8_t* bgra, int w, int h, int stride,
    bool grayscale)
{
#ifdef PDFIUM4J_HAS_PNG
    int channels = grayscale ? 1 : 3;
    std::vector<uint8_t> raw(w * h * channels);
    for (int y = 0; y < h; ++y) {
        const uint8_t* src = bgra + y * stride;
        uint8_t* dst = raw.data() + y * w * channels;
        if (grayscale) {
            for (int x = 0; x < w; ++x)
                dst[x] = (uint8_t)(src[x*4]*0.114f + src[x*4+1]*0.587f + src[x*4+2]*0.299f);
        } else {
            for (int x = 0; x < w; ++x) {
                dst[x*3+0] = src[x*4+2];
                dst[x*3+1] = src[x*4+1];
                dst[x*3+2] = src[x*4+0];
            }
        }
    }

    uLongf bound = compressBound(raw.size());
    std::vector<uint8_t> compressed(bound);
    
    z_stream zs = {};
    if (deflateInit2(&zs, 6, Z_DEFLATED, 15, 8, Z_FILTERED) != Z_OK) {
        throw std::runtime_error("deflateInit2 failed");
    }
    
    zs.next_in = raw.data();
    zs.avail_in = (uInt)raw.size();
    zs.next_out = compressed.data();
    zs.avail_out = (uInt)bound;
    
    int rc = deflate(&zs, Z_FINISH);
    if (rc != Z_STREAM_END) {
        deflateEnd(&zs);
        throw std::runtime_error("deflate failed");
    }
    
    uLongf final_size = zs.total_out;
    deflateEnd(&zs);
    compressed.resize(final_size);
    return compressed;
#else
    throw std::runtime_error("ZLIB support not compiled in");
#endif
}

// Raster: inject image into a QPDF page

static void injectRasterPage(
    QPDF& qpdf,
    QPDFObjectHandle& pageObj,
    const pdfium4j_flatten_config_t* cfg,
    const uint8_t* pixels, int px_w, int px_h, int stride,
    double pt_w, double pt_h,
    std::vector<std::vector<uint8_t>>& imageStore)
{
    bool lossless = (cfg->encoding == 2 || cfg->encoding == 4);
    bool grayscale = cfg->grayscale != 0;
    bool alpha     = cfg->alphaMaskEnabled != 0 && lossless;

    imageStore.emplace_back();
    std::vector<uint8_t>& imageData = imageStore.back();
    std::string filter;
    std::string colorSpace = grayscale ? "/DeviceGray" : "/DeviceRGB";

    switch (cfg->encoding) {
        case 0: // JPEG
        case 1: // JPEG2000 (fallback to JPEG)
            imageData = encodeJPEG(pixels, px_w, px_h, stride,
                                   cfg->jpegQuality, grayscale);
            filter = "/DCTDecode";
            break;
        case 2: // PNG
            imageData = encodePNG(pixels, px_w, px_h, stride,
                                  cfg->pngCompressionLevel, grayscale, alpha);
            filter = "/FlateDecode";
            break;
        case 4: // FLATE
        case 3: // JBIG2 (fallback to FLATE)
            imageData = encodeFLATE(pixels, px_w, px_h, stride, grayscale);
            filter = "/FlateDecode";
            break;
    }

    QPDFObjectHandle imgDict = QPDFObjectHandle::newDictionary();
    imgDict.replaceKey("/Type",             QPDFObjectHandle::newName("/XObject"));
    imgDict.replaceKey("/Subtype",          QPDFObjectHandle::newName("/Image"));
    imgDict.replaceKey("/Width",            QPDFObjectHandle::newInteger(px_w));
    imgDict.replaceKey("/Height",           QPDFObjectHandle::newInteger(px_h));
    imgDict.replaceKey("/ColorSpace",       QPDFObjectHandle::newName(colorSpace));
    imgDict.replaceKey("/BitsPerComponent", QPDFObjectHandle::newInteger(8));
    imgDict.replaceKey("/Filter",           QPDFObjectHandle::newName(filter));

    auto imgStream = QPDFObjectHandle::newStream(&qpdf);
    imgStream.replaceDict(imgDict);
    imgStream.replaceStreamData(
        std::make_shared<BorrowedStreamData>(imageData),
        QPDFObjectHandle::newName(filter),
        QPDFObjectHandle::newNull());

    QPDFObjectHandle xobj  = QPDFObjectHandle::newDictionary();
    xobj.replaceKey("/Im0", imgStream);

    QPDFObjectHandle res   = QPDFObjectHandle::newDictionary();
    res.replaceKey("/XObject", xobj);

    std::ostringstream cs;
    cs << "q\n"
       << pt_w << " 0 0 " << pt_h << " 0 0 cm\n"
       << "/Im0 Do\n"
       << "Q\n";
    std::string csStr = cs.str();

    QPDFObjectHandle contentStream = QPDFObjectHandle::newStream(&qpdf, csStr);

    pageObj.replaceKey("/MediaBox",
        QPDFObjectHandle::parse("[0 0 "
            + std::to_string(pt_w) + " "
            + std::to_string(pt_h) + "]"));
    pageObj.removeKey("/CropBox");
    pageObj.removeKey("/BleedBox");
    pageObj.removeKey("/TrimBox");
    pageObj.removeKey("/ArtBox");
    pageObj.removeKey("/Annots");
    pageObj.removeKey("/AcroForm");
    pageObj.replaceKey("/Resources", res);
    pageObj.replaceKey("/Contents",  contentStream);
}

// Logical flatten via PDFium

static std::vector<uint8_t> logicalFlattenViaPDFium(
    const uint8_t* inputPDF, size_t inputLen,
    const pdfium4j_flatten_config_t* cfg)
{
    FPDF_DOCUMENT doc = FPDF_LoadMemDocument(inputPDF, (int)inputLen, nullptr);
    if (!doc) {
        throw std::runtime_error("PDFium: failed to load document");
    }

    int total = FPDF_GetPageCount(doc);
    int from  = cfg->pageFrom;
    int to    = (cfg->pageTo < 0) ? total - 1 : std::min(cfg->pageTo, total - 1);

    for (int i = from; i <= to; ++i) {
        FPDF_PAGE page = FPDF_LoadPage(doc, i);
        if (!page) continue;

        int flag = cfg->flattenForPrint ? FLAT_PRINT : FLAT_NORMALDISPLAY;
        FPDFPage_Flatten(page, flag);

        FPDF_ClosePage(page);
    }

    std::vector<uint8_t> output;
    PDFiumWriteCtx wctx(output);
    FPDF_SaveAsCopy(doc, &wctx.iface, FPDF_NO_INCREMENTAL);

    FPDF_CloseDocument(doc);
    return output;
}

// Raster flatten pipeline

static std::vector<uint8_t> rasterFlattenPipeline(
    const uint8_t* inputPDF, size_t inputLen,
    const pdfium4j_flatten_config_t* cfg)
{
    FPDF_DOCUMENT doc = FPDF_LoadMemDocument(inputPDF, (int)inputLen, nullptr);
    if (!doc) {
        throw std::runtime_error("PDFium: failed to load document");
    }

    int renderFlags = 0;
    if (cfg->renderAnnotations) renderFlags |= FPDF_ANNOT;
    if (cfg->renderForms)       renderFlags |= FPDF_ANNOT; // Use ANNOT for forms as well
    if (cfg->renderLCDText)     renderFlags |= FPDF_LCD_TEXT;
    if (cfg->noNativeText)      renderFlags |= FPDF_NO_NATIVETEXT;
    if (cfg->printingMode)      renderFlags |= FPDF_PRINTING;
    if (cfg->reverseByteOrder)  renderFlags |= FPDF_REVERSE_BYTE_ORDER;

    int total = FPDF_GetPageCount(doc);
    int from  = cfg->pageFrom;
    int to    = (cfg->pageTo < 0) ? total - 1 : std::min(cfg->pageTo, total - 1);

    QPDF qpdf;
    qpdf.processMemoryFile("input.pdf", reinterpret_cast<const char*>(inputPDF), inputLen);

    QPDFPageDocumentHelper pdh(qpdf);
    auto pages = pdh.getAllPages();
    std::vector<std::vector<uint8_t>> imageStore;
    imageStore.reserve(to - from + 1);

    for (int i = from; i <= to; ++i) {
        FPDF_PAGE page = FPDF_LoadPage(doc, i);
        if (!page) continue;

        double pt_w = FPDF_GetPageWidth(page);
        double pt_h = FPDF_GetPageHeight(page);

        float scale = cfg->scale;
        int px_w = (int)std::ceil(pt_w / 72.0 * cfg->dpi * scale);
        int px_h = (int)std::ceil(pt_h / 72.0 * cfg->dpi * scale);

        // Align stride to 64 bytes for SIMD optimization
        int stride = ((px_w * 4 + 63) / 64) * 64;
        
        void* buf = nullptr;
#ifdef _WIN32
        buf = _aligned_malloc(stride * px_h, 64);
#else
        if (posix_memalign(&buf, 64, (size_t)stride * px_h) != 0) buf = nullptr;
#endif
        if (!buf) {
            FPDF_ClosePage(page);
            throw std::runtime_error("Failed to allocate aligned bitmap buffer");
        }

        FPDF_BITMAP bitmap = FPDFBitmap_CreateEx(
            px_w, px_h, FPDFBitmap_BGRA,
            buf, stride);

        FPDFBitmap_FillRect(bitmap, 0, 0, px_w, px_h, 0xFFFFFFFF);

        FPDF_RenderPageBitmap(bitmap, page,
            0, 0, px_w, px_h,
            0,
            renderFlags);

        const uint8_t* pixels = static_cast<const uint8_t*>(
            FPDFBitmap_GetBuffer(bitmap));
        int actualStride = FPDFBitmap_GetStride(bitmap);

        auto& qpdfPage = pages[i];
        QPDFObjectHandle pageObj = qpdfPage.getObjectHandle();
        injectRasterPage(qpdf, pageObj, cfg,
                         pixels, px_w, px_h, actualStride,
                         pt_w, pt_h, imageStore);

        FPDFBitmap_Destroy(bitmap);
#ifdef _WIN32
        _aligned_free(buf);
#else
        free(buf);
#endif
        FPDF_ClosePage(page);
    }

    FPDF_CloseDocument(doc);

    std::vector<uint8_t> output;
    VectorPipeline vp(output);
    QPDFWriter writer(qpdf);
    writer.setOutputPipeline(&vp);
    writer.setCompressStreams(cfg->compressStreams != 0);
    if (cfg->linearize != 0) writer.setLinearization(true);
    if (cfg->preserveMetadata == 0) {
        qpdf.getRoot().removeKey("/Metadata");
    }
    writer.write();
    return output;
}

// Exported C API

SHIM_EXPORT int FPDF_CALLCONV pdfium4j_flatten_advanced(
    const uint8_t* in_pdf, size_t in_len,
    const pdfium4j_flatten_config_t* config,
    uint8_t** out_pdf, size_t* out_len)
{
    try {
        std::vector<uint8_t> result;

        if (config->mode == 1) { // LOGICAL
            result = logicalFlattenViaPDFium(in_pdf, in_len, config);
        } else if (config->mode == 0) { // RASTER
            result = rasterFlattenPipeline(in_pdf, in_len, config);
        } else if (config->mode == 2) { // BOTH
            auto logicalOut = logicalFlattenViaPDFium(in_pdf, in_len, config);
            result = rasterFlattenPipeline(logicalOut.data(), logicalOut.size(), config);
        } else {
            return -1; // Invalid mode
        }

        *out_len = result.size();
        *out_pdf = static_cast<uint8_t*>(malloc(result.size()));
        if (!*out_pdf) return -2; // Out of memory

        std::memcpy(*out_pdf, result.data(), result.size());
        return 0; // Success

    } catch (...) {
        return -3; // Unknown error
    }
}

SHIM_EXPORT void FPDF_CALLCONV pdfium4j_free_buffer(uint8_t* buf)
{
    if (buf) {
        free(buf);
    }
}

SHIM_EXPORT int FPDF_CALLCONV pdfium4j_encode_jpeg(
    const uint8_t* bgra, int w, int h, int stride,
    int quality, int grayscale,
    uint8_t** out_bytes, size_t* out_len)
{
    try {
        auto result = encodeJPEG(bgra, w, h, stride, quality, grayscale != 0);
        *out_len = result.size();
        *out_bytes = static_cast<uint8_t*>(malloc(result.size()));
        if (!*out_bytes) return -2;
        std::memcpy(*out_bytes, result.data(), result.size());
        return 0;
    } catch (...) {
        return -1;
    }
}

SHIM_EXPORT int FPDF_CALLCONV pdfium4j_encode_png(
    const uint8_t* bgra, int w, int h, int stride,
    int compression_level, int grayscale, int alpha,
    uint8_t** out_bytes, size_t* out_len)
{
    try {
        auto result = encodePNG(bgra, w, h, stride, compression_level, grayscale != 0, alpha != 0);
        *out_len = result.size();
        *out_bytes = static_cast<uint8_t*>(malloc(result.size()));
        if (!*out_bytes) return -2;
        std::memcpy(*out_bytes, result.data(), result.size());
        return 0;
    } catch (...) {
        return -1;
    }
}
