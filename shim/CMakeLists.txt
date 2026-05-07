cmake_minimum_required(VERSION 3.15)
project(pdfium4j_shim VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find PDFium headers and library
# These will be provided via Gradle
if(NOT PDFIUM_ROOT)
    message(FATAL_ERROR "PDFIUM_ROOT must be set")
endif()

include_directories(${PDFIUM_ROOT}/include)
include_directories(include)
include_directories(vendor/pugixml)

add_library(pdfium4j_shim SHARED
    src/pdfium4j_meta.cpp
    src/pdfium4j_doc.cpp
    src/pdfium4j_save.cpp
    vendor/pugixml/pugixml.cpp
)

find_library(PDFIUM_LIB pdfium PATHS ${PDFIUM_ROOT}/lib ${PDFIUM_ROOT}/bin NO_DEFAULT_PATH)

if(NOT PDFIUM_LIB)
    message(FATAL_ERROR "Could not find pdfium library in ${PDFIUM_ROOT}")
endif()

target_link_libraries(pdfium4j_shim PRIVATE ${PDFIUM_LIB})

# Ensure the output name is consistent
set_target_properties(pdfium4j_shim PROPERTIES
    PREFIX ""
    OUTPUT_NAME "pdfium4j_shim"
)
