#include <limits.h>
#include <stdint.h>
#include <stddef.h>

#ifdef _WIN32
#include <io.h>
#define PDFIUM4J_WRITE _write
#define PDFIUM4J_ERRNO() (-1)
#define PDFIUM4J_EXPORT __declspec(dllexport)
#else
#include <dlfcn.h>
#include <errno.h>
#include <unistd.h>
#define PDFIUM4J_WRITE write
#define PDFIUM4J_ERRNO() errno
#define PDFIUM4J_EXPORT __attribute__((visibility("default")))
#endif

typedef void* FPDF_DOCUMENT;

typedef struct FPDF_FILEWRITE_ FPDF_FILEWRITE;
struct FPDF_FILEWRITE_ {
  int version;
  int (*WriteBlock)(FPDF_FILEWRITE* pThis, const void* pData, unsigned long size);
};

typedef int (*FPDF_SAVE_AS_COPY_FN)(FPDF_DOCUMENT, FPDF_FILEWRITE*, int);
typedef int (*FPDF_SAVE_WITH_VERSION_FN)(FPDF_DOCUMENT, FPDF_FILEWRITE*, int, int);

typedef struct PDFIUM4J_FILEWRITE_ {
  int version;
  int (*WriteBlock)(FPDF_FILEWRITE* pThis, const void* pData, unsigned long size);
  int fd;
  int error_code;
} PDFIUM4J_FILEWRITE;

static int pdfium4j_write_block(FPDF_FILEWRITE* pThis, const void* pData, unsigned long size) {
  PDFIUM4J_FILEWRITE* file_write = (PDFIUM4J_FILEWRITE*) pThis;
  const unsigned char* data = (const unsigned char*) pData;
  size_t remaining = (size_t) size;
  while (remaining > 0) {
    unsigned int chunk = remaining > (size_t) INT_MAX ? (unsigned int) INT_MAX : (unsigned int) remaining;
    int written = PDFIUM4J_WRITE(file_write->fd, data, chunk);
    if (written <= 0) {
      file_write->error_code = PDFIUM4J_ERRNO();
      return 0;
    }
    data += written;
    remaining -= (size_t) written;
  }
  return 1;
}

#ifndef _WIN32
static void* pdfium4j_lookup_symbol(const char* symbol_name) {
  return dlsym(RTLD_DEFAULT, symbol_name);
}
#endif

PDFIUM4J_EXPORT int pdfium4j_save_to_fd(
    void* doc_handle,
    int fd,
    int flags,
    int version,
    int use_save_with_version) {
  if (doc_handle == NULL || fd < 0) {
    return -1;
  }

  PDFIUM4J_FILEWRITE file_write;
  file_write.version = 1;
  file_write.WriteBlock = pdfium4j_write_block;
  file_write.fd = fd;
  file_write.error_code = 0;

#ifdef _WIN32
  (void) version;
  (void) use_save_with_version;
  return -1;
#else
  if (use_save_with_version) {
    FPDF_SAVE_WITH_VERSION_FN save_with_version =
        (FPDF_SAVE_WITH_VERSION_FN) pdfium4j_lookup_symbol("FPDF_SaveWithVersion");
    if (save_with_version != NULL) {
      int ok = save_with_version((FPDF_DOCUMENT) doc_handle, (FPDF_FILEWRITE*) &file_write, flags, version);
      if (ok != 0) {
        return 1;
      }
      return file_write.error_code == 0 ? 0 : -file_write.error_code;
    }
  }

  FPDF_SAVE_AS_COPY_FN save_as_copy =
      (FPDF_SAVE_AS_COPY_FN) pdfium4j_lookup_symbol("FPDF_SaveAsCopy");
  if (save_as_copy == NULL) {
    return -1;
  }
  int ok = save_as_copy((FPDF_DOCUMENT) doc_handle, (FPDF_FILEWRITE*) &file_write, flags);
  if (ok != 0) {
    return 1;
  }
  return file_write.error_code == 0 ? 0 : -file_write.error_code;
#endif
}