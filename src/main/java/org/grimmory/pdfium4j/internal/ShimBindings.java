package org.grimmory.pdfium4j.internal;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_INT;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_POINTER;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.StableValue;
import java.util.Objects;
/** FFM bindings for the pdfium4j C++ shim library. */
public final class ShimBindings {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();
  private ShimBindings() {}
  /** Ensures all required shim symbols are available. */
  public static void checkRequired() {
    Objects.requireNonNull(pdfium4j_page_count(), "pdfium4j_page_count");
  }
  private static MethodHandle find(String name, FunctionDescriptor desc, boolean critical) {
    java.lang.foreign.MemorySegment addr = LOOKUP.find(name).orElse(null);
    if (addr == null) return null;
    return LINKER.downcallHandle(addr, desc, critical ? FfmHelper.CRITICAL_OPTIONS : FfmHelper.NO_OPTIONS);
  }
  private static final StableValue<MethodHandle> pdfium4j_page_count_SV = StableValue.of();
  public static MethodHandle pdfium4j_page_count() {
    return pdfium4j_page_count_SV.orElseSet(
        () -> find("pdfium4j_page_count", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }
  private static final StableValue<MethodHandle> pdfium4j_get_meta_utf8_SV = StableValue.of();
  public static MethodHandle pdfium4j_get_meta_utf8() {
    return pdfium4j_get_meta_utf8_SV.orElseSet(
        () ->
            find(
                "pdfium4j_get_meta_utf8",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_POINTER, C_INT),
                false));
  }
  private static final StableValue<MethodHandle> pdfium4j_set_meta_utf8_SV = StableValue.of();
  public static MethodHandle pdfium4j_set_meta_utf8() {
    return pdfium4j_set_meta_utf8_SV.orElseSet(
        () ->
            find(
                "pdfium4j_set_meta_utf8",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_POINTER),
                false));
  }
  private static final StableValue<MethodHandle> pdfium4j_get_xmp_metadata_SV = StableValue.of();
  public static MethodHandle pdfium4j_get_xmp_metadata() {
    return pdfium4j_get_xmp_metadata_SV.orElseSet(
        () ->
            find(
                "pdfium4j_get_xmp_metadata",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_INT),
                false));
  }
  private static final StableValue<MethodHandle> pdfium4j_page_label_SV = StableValue.of();
  public static MethodHandle pdfium4j_page_label() {
    return pdfium4j_page_label_SV.orElseSet(
        () ->
            find(
                "pdfium4j_page_label",
                FunctionDescriptor.of(C_INT, C_POINTER, C_INT, C_POINTER, C_INT),
                false));
  }
  private static final StableValue<MethodHandle> pdfium4j_page_width_SV = StableValue.of();
  public static MethodHandle pdfium4j_page_width() {
    return pdfium4j_page_width_SV.orElseSet(
        () -> find("pdfium4j_page_width", FunctionDescriptor.of(ValueLayout.JAVA_FLOAT, C_POINTER, C_INT), true));
  }
  private static final StableValue<MethodHandle> pdfium4j_page_height_SV = StableValue.of();
  public static MethodHandle pdfium4j_page_height() {
    return pdfium4j_page_height_SV.orElseSet(
        () ->
            find("pdfium4j_page_height", FunctionDescriptor.of(ValueLayout.JAVA_FLOAT, C_POINTER, C_INT), true));
  }
  private static final StableValue<MethodHandle> pdfium4j_bookmark_first_SV = StableValue.of();
  public static MethodHandle pdfium4j_bookmark_first() {
    return pdfium4j_bookmark_first_SV.orElseSet(
        () -> find("pdfium4j_bookmark_first", FunctionDescriptor.of(C_POINTER, C_POINTER), true));
  }
  private static final StableValue<MethodHandle> pdfium4j_bookmark_next_SV = StableValue.of();
  public static MethodHandle pdfium4j_bookmark_next() {
    return pdfium4j_bookmark_next_SV.orElseSet(
        () ->
            find(
                "pdfium4j_bookmark_next",
                FunctionDescriptor.of(C_POINTER, C_POINTER, C_POINTER),
                true));
  }
  private static final StableValue<MethodHandle> pdfium4j_bookmark_first_child_SV = StableValue.of();
  public static MethodHandle pdfium4j_bookmark_first_child() {
    return pdfium4j_bookmark_first_child_SV.orElseSet(
        () ->
            find(
                "pdfium4j_bookmark_first_child",
                FunctionDescriptor.of(C_POINTER, C_POINTER, C_POINTER),
                true));
  }
  private static final StableValue<MethodHandle> pdfium4j_bookmark_title_SV = StableValue.of();
  public static MethodHandle pdfium4j_bookmark_title() {
    return pdfium4j_bookmark_title_SV.orElseSet(
        () ->
            find(
                "pdfium4j_bookmark_title",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_INT),
                false));
  }
  private static final StableValue<MethodHandle> pdfium4j_bookmark_page_index_SV = StableValue.of();
  public static MethodHandle pdfium4j_bookmark_page_index() {
    return pdfium4j_bookmark_page_index_SV.orElseSet(
        () ->
            find(
                "pdfium4j_bookmark_page_index",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER),
                true));
  }
  private static final StableValue<MethodHandle> pdfium4j_struct_tree_get_SV = StableValue.of();
  public static MethodHandle pdfium4j_struct_tree_get() {
    return pdfium4j_struct_tree_get_SV.orElseSet(
        () -> find("pdfium4j_struct_tree_get", FunctionDescriptor.of(C_POINTER, C_POINTER), false));
  }
  private static final StableValue<MethodHandle> pdfium4j_struct_tree_close_SV = StableValue.of();
  public static MethodHandle pdfium4j_struct_tree_close() {
    return pdfium4j_struct_tree_close_SV.orElseSet(
        () -> find("pdfium4j_struct_tree_close", FunctionDescriptor.ofVoid(C_POINTER), false));
  }
  private static final StableValue<MethodHandle> pdfium4j_struct_tree_count_children_SV =
      StableValue.of();
  public static MethodHandle pdfium4j_struct_tree_count_children() {
    return pdfium4j_struct_tree_count_children_SV.orElseSet(
        () ->
            find("pdfium4j_struct_tree_count_children", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }
  private static final StableValue<MethodHandle> pdfium4j_struct_tree_get_child_SV =
      StableValue.of();
  public static MethodHandle pdfium4j_struct_tree_get_child() {
    return pdfium4j_struct_tree_get_child_SV.orElseSet(
        () ->
            find(
                "pdfium4j_struct_tree_get_child",
                FunctionDescriptor.of(C_POINTER, C_POINTER, C_INT),
                true));
  }
  private static final StableValue<MethodHandle> pdfium4j_struct_element_count_children_SV =
      StableValue.of();
  public static MethodHandle pdfium4j_struct_element_count_children() {
    return pdfium4j_struct_element_count_children_SV.orElseSet(
        () ->
            find(
                "pdfium4j_struct_element_count_children",
                FunctionDescriptor.of(C_INT, C_POINTER),
                true));
  }
  private static final StableValue<MethodHandle> pdfium4j_struct_element_get_child_SV =
      StableValue.of();
  public static MethodHandle pdfium4j_struct_element_get_child() {
    return pdfium4j_struct_element_get_child_SV.orElseSet(
        () ->
            find(
                "pdfium4j_struct_element_get_child",
                FunctionDescriptor.of(C_POINTER, C_POINTER, C_INT),
                true));
  }
  private static final StableValue<MethodHandle> pdfium4j_struct_element_get_mcid_SV =
      StableValue.of();
  public static MethodHandle pdfium4j_struct_element_get_mcid() {
    return pdfium4j_struct_element_get_mcid_SV.orElseSet(
        () ->
            find(
                "pdfium4j_struct_element_get_mcid",
                FunctionDescriptor.of(C_INT, C_POINTER, C_INT),
                true));
  }
  private static final StableValue<MethodHandle> pdfium4j_struct_element_get_type_SV =
      StableValue.of();
  public static MethodHandle pdfium4j_struct_element_get_type() {
    return pdfium4j_struct_element_get_type_SV.orElseSet(
        () ->
            find(
                "pdfium4j_struct_element_get_type",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_INT),
                false));
  }
  private static final StableValue<MethodHandle> pdfium4j_struct_element_get_title_SV =
      StableValue.of();
  public static MethodHandle pdfium4j_struct_element_get_title() {
    return pdfium4j_struct_element_get_title_SV.orElseSet(
        () ->
            find(
                "pdfium4j_struct_element_get_title",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_INT),
                false));
  }
  private static final StableValue<MethodHandle> pdfium4j_struct_element_get_alt_text_SV =
      StableValue.of();
  public static MethodHandle pdfium4j_struct_element_get_alt_text() {
    return pdfium4j_struct_element_get_alt_text_SV.orElseSet(
        () ->
            find(
                "pdfium4j_struct_element_get_alt_text",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_INT),
                false));
  }
  private static final StableValue<MethodHandle> pdfium4j_struct_element_get_actual_text_SV =
      StableValue.of();
  public static MethodHandle pdfium4j_struct_element_get_actual_text() {
    return pdfium4j_struct_element_get_actual_text_SV.orElseSet(
        () ->
            find(
                "pdfium4j_struct_element_get_actual_text",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_INT),
                false));
  }
  private static final StableValue<MethodHandle> pdfium4j_struct_element_get_lang_SV =
      StableValue.of();
  public static MethodHandle pdfium4j_struct_element_get_lang() {
    return pdfium4j_struct_element_get_lang_SV.orElseSet(
        () ->
            find(
                "pdfium4j_struct_element_get_lang",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_INT),
                false));
  }
  private static final StableValue<MethodHandle> pdfium4j_struct_element_get_attribute_count_SV =
      StableValue.of();
  public static MethodHandle pdfium4j_struct_element_get_attribute_count() {
    return pdfium4j_struct_element_get_attribute_count_SV.orElseSet(
        () ->
            find(
                "pdfium4j_struct_element_get_attribute_count",
                FunctionDescriptor.of(C_INT, C_POINTER),
                false));
  }
  private static final StableValue<MethodHandle> pdfium4j_text_get_chars_with_bounds_SV =
      StableValue.of();
  public static MethodHandle pdfium4j_text_get_chars_with_bounds() {
    return pdfium4j_text_get_chars_with_bounds_SV.orElseSet(
        () ->
            find(
                "pdfium4j_text_get_chars_with_bounds",
                FunctionDescriptor.of(C_INT, C_POINTER, C_INT, C_INT, C_POINTER),
                false));
  }
  private static final StableValue<MethodHandle> pdfium4j_save_incremental_SV = StableValue.of();
  public static MethodHandle pdfium4j_save_incremental() {
    return pdfium4j_save_incremental_SV.orElseSet(
        () -> find("pdfium4j_save_incremental", FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER), false));
  }
  private static final StableValue<MethodHandle> pdfium4j_save_copy_SV = StableValue.of();
  public static MethodHandle pdfium4j_save_copy() {
    return pdfium4j_save_copy_SV.orElseSet(
        () -> find("pdfium4j_save_copy", FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER), false));
  }
}
