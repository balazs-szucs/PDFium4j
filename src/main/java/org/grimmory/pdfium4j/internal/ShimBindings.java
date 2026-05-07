package org.grimmory.pdfium4j.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/** FFM bindings for the pdfium4j C++ shim library. */
public final class ShimBindings {

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

  private ShimBindings() {}
 
  /** Ensures all required shim symbols are available. */
  public static void checkRequired() {
    // Accessing any static field will trigger class initialization and symbol lookup
    if (pdfium4j_page_count == null) {
      throw new UnsatisfiedLinkError("Shim bindings failed to initialize");
    }
  }

  private static MethodHandle downcall(String name, FunctionDescriptor desc) {
    return LOOKUP
        .find(name)
        .map(addr -> LINKER.downcallHandle(addr, desc))
        .orElseThrow(() -> new UnsatisfiedLinkError("Shim symbol not found: " + name));
  }

  private static MethodHandle downcallCritical(String name, FunctionDescriptor desc) {
    return LOOKUP
        .find(name)
        .map(addr -> LINKER.downcallHandle(addr, desc, Linker.Option.critical(false)))
        .orElseThrow(() -> new UnsatisfiedLinkError("Shim symbol not found: " + name));
  }

  public static final MethodHandle pdfium4j_page_count =
      downcallCritical("pdfium4j_page_count", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  public static final MethodHandle pdfium4j_get_meta_utf8 =
      downcall(
          "pdfium4j_get_meta_utf8",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));

  public static final MethodHandle pdfium4j_set_meta_utf8 =
      downcall(
          "pdfium4j_set_meta_utf8", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

  public static final MethodHandle pdfium4j_set_custom_xmp =
      downcall(
          "pdfium4j_set_custom_xmp",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  public static final MethodHandle pdfium4j_get_custom_xmp =
      downcall(
          "pdfium4j_get_custom_xmp",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));

  public static final MethodHandle pdfium4j_get_xmp_metadata =
      downcall(
          "pdfium4j_get_xmp_metadata", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

  public static final MethodHandle pdfium4j_get_custom_xmp_bag =
      downcall(
          "pdfium4j_get_custom_xmp_bag",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));

  public static final MethodHandle pdfium4j_set_custom_xmp_bag =
      downcall(
          "pdfium4j_set_custom_xmp_bag",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  public static final MethodHandle pdfium4j_page_label =
      downcall(
          "pdfium4j_page_label",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));

  public static final MethodHandle pdfium4j_page_width =
      downcallCritical("pdfium4j_page_width", FunctionDescriptor.of(JAVA_FLOAT, ADDRESS, JAVA_INT));

  public static final MethodHandle pdfium4j_page_height =
      downcallCritical(
          "pdfium4j_page_height", FunctionDescriptor.of(JAVA_FLOAT, ADDRESS, JAVA_INT));

  public static final MethodHandle pdfium4j_bookmark_first =
      downcall("pdfium4j_bookmark_first", FunctionDescriptor.of(ADDRESS, ADDRESS));

  public static final MethodHandle pdfium4j_bookmark_next =
      downcall("pdfium4j_bookmark_next", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

  public static final MethodHandle pdfium4j_bookmark_first_child =
      downcall("pdfium4j_bookmark_first_child", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

  public static final MethodHandle pdfium4j_bookmark_title =
      downcall(
          "pdfium4j_bookmark_title", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

  public static final MethodHandle pdfium4j_bookmark_page_index =
      downcall("pdfium4j_bookmark_page_index", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

  public static final MethodHandle pdfium4j_struct_tree_get =
      downcall("pdfium4j_struct_tree_get", FunctionDescriptor.of(ADDRESS, ADDRESS));

  public static final MethodHandle pdfium4j_struct_tree_close =
      downcall("pdfium4j_struct_tree_close", FunctionDescriptor.ofVoid(ADDRESS));

  public static final MethodHandle pdfium4j_struct_tree_count_children =
      downcall("pdfium4j_struct_tree_count_children", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  public static final MethodHandle pdfium4j_struct_tree_get_child =
      downcall("pdfium4j_struct_tree_get_child", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

  public static final MethodHandle pdfium4j_struct_element_count_children =
      downcall("pdfium4j_struct_element_count_children", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  public static final MethodHandle pdfium4j_struct_element_get_child =
      downcall(
          "pdfium4j_struct_element_get_child", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

  public static final MethodHandle pdfium4j_struct_element_get_mcid =
      downcall(
          "pdfium4j_struct_element_get_mcid", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

  public static final MethodHandle pdfium4j_struct_element_get_type =
      downcall(
          "pdfium4j_struct_element_get_type",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

  public static final MethodHandle pdfium4j_struct_element_get_title =
      downcall(
          "pdfium4j_struct_element_get_title",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

  public static final MethodHandle pdfium4j_struct_element_get_alt_text =
      downcall(
          "pdfium4j_struct_element_get_alt_text",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

  public static final MethodHandle pdfium4j_struct_element_get_actual_text =
      downcall(
          "pdfium4j_struct_element_get_actual_text",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

  public static final MethodHandle pdfium4j_struct_element_get_lang =
      downcall(
          "pdfium4j_struct_element_get_lang",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

  public static final MethodHandle pdfium4j_struct_element_get_attribute_count =
      downcall(
          "pdfium4j_struct_element_get_attribute_count", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  public static final MethodHandle pdfium4j_text_get_chars_with_bounds =
      downcall(
          "pdfium4j_text_get_chars_with_bounds",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));

  public static final MethodHandle pdfium4j_save_incremental =
      downcall("pdfium4j_save_incremental", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

  public static final MethodHandle pdfium4j_save_copy =
      downcall("pdfium4j_save_copy", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
}
