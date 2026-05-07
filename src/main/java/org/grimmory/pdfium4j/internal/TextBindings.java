package org.grimmory.pdfium4j.internal;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_INT;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_POINTER;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.StableValue;
import java.util.Objects;
/** FFM bindings for PDFium text extraction functions from {@code fpdf_text.h}. */
public final class TextBindings {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();
  private TextBindings() {}
  private static MethodHandle find(String name, FunctionDescriptor desc, boolean critical) {
    java.lang.foreign.MemorySegment addr = LOOKUP.find(name).orElse(null);
    if (addr == null) return null;
    return LINKER.downcallHandle(addr, desc, critical ? FfmHelper.CRITICAL_OPTIONS : FfmHelper.NO_OPTIONS);
  }
  public static void checkRequired() {
    Objects.requireNonNull(FPDFText_LoadPage(), "FPDFText_LoadPage");
    Objects.requireNonNull(FPDFText_ClosePage(), "FPDFText_ClosePage");
    Objects.requireNonNull(FPDFText_CountChars(), "FPDFText_CountChars");
    Objects.requireNonNull(FPDFLink_LoadWebLinks(), "FPDFLink_LoadWebLinks");
    Objects.requireNonNull(FPDFLink_CountWebLinks(), "FPDFLink_CountWebLinks");
    Objects.requireNonNull(FPDFLink_GetURL(), "FPDFLink_GetURL");
    Objects.requireNonNull(FPDFLink_CountRects(), "FPDFLink_CountRects");
    Objects.requireNonNull(FPDFLink_GetRect(), "FPDFLink_GetRect");
    Objects.requireNonNull(FPDFLink_CloseWebLinks(), "FPDFLink_CloseWebLinks");
  }
  private static final StableValue<MethodHandle> FPDFText_LoadPage_SV = StableValue.of();
  public static MethodHandle FPDFText_LoadPage() {
    return FPDFText_LoadPage_SV.orElseSet(
        () -> find("FPDFText_LoadPage", FunctionDescriptor.of(C_POINTER, C_POINTER), false));
  }
  private static final StableValue<MethodHandle> FPDFText_ClosePage_SV = StableValue.of();
  public static MethodHandle FPDFText_ClosePage() {
    return FPDFText_ClosePage_SV.orElseSet(
        () -> find("FPDFText_ClosePage", FunctionDescriptor.ofVoid(C_POINTER), false));
  }
  private static final StableValue<MethodHandle> FPDFText_CountChars_SV = StableValue.of();
  public static MethodHandle FPDFText_CountChars() {
    return FPDFText_CountChars_SV.orElseSet(
        () -> find("FPDFText_CountChars", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }
  private static final StableValue<MethodHandle> FPDFText_GetText_SV = StableValue.of();
  public static MethodHandle FPDFText_GetText() {
    return FPDFText_GetText_SV.orElseSet(
        () ->
            find(
                "FPDFText_GetText",
                FunctionDescriptor.of(C_INT, C_POINTER, C_INT, C_INT, C_POINTER),
                false));
  }
  private static final StableValue<MethodHandle> FPDFLink_LoadWebLinks_SV = StableValue.of();
  public static MethodHandle FPDFLink_LoadWebLinks() {
    return FPDFLink_LoadWebLinks_SV.orElseSet(
        () -> find("FPDFLink_LoadWebLinks", FunctionDescriptor.of(C_POINTER, C_POINTER), false));
  }
  private static final StableValue<MethodHandle> FPDFLink_CountWebLinks_SV = StableValue.of();
  public static MethodHandle FPDFLink_CountWebLinks() {
    return FPDFLink_CountWebLinks_SV.orElseSet(
        () -> find("FPDFLink_CountWebLinks", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }
  private static final StableValue<MethodHandle> FPDFLink_GetURL_SV = StableValue.of();
  public static MethodHandle FPDFLink_GetURL() {
    return FPDFLink_GetURL_SV.orElseSet(
        () ->
            find(
                "FPDFLink_GetURL",
                FunctionDescriptor.of(C_INT, C_POINTER, C_INT, C_POINTER, C_INT),
                false));
  }
  private static final StableValue<MethodHandle> FPDFLink_CountRects_SV = StableValue.of();
  public static MethodHandle FPDFLink_CountRects() {
    return FPDFLink_CountRects_SV.orElseSet(
        () -> find("FPDFLink_CountRects", FunctionDescriptor.of(C_INT, C_POINTER, C_INT), true));
  }
  private static final StableValue<MethodHandle> FPDFLink_GetRect_SV = StableValue.of();
  public static MethodHandle FPDFLink_GetRect() {
    return FPDFLink_GetRect_SV.orElseSet(
        () ->
            find(
                "FPDFLink_GetRect",
                FunctionDescriptor.of(
                    C_INT, C_POINTER, C_INT, C_INT, C_POINTER, C_POINTER, C_POINTER, C_POINTER),
                false));
  }
  private static final StableValue<MethodHandle> FPDFLink_CloseWebLinks_SV = StableValue.of();
  public static MethodHandle FPDFLink_CloseWebLinks() {
    return FPDFLink_CloseWebLinks_SV.orElseSet(
        () -> find("FPDFLink_CloseWebLinks", FunctionDescriptor.ofVoid(C_POINTER), false));
  }
}
