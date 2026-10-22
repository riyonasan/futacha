cmake_minimum_required(VERSION 3.21)
project(FutachaOrbTest LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(BUILD_LIST "core,imgproc,features,geometry,flann" CACHE STRING "" FORCE)
foreach(option BUILD_SHARED_LIBS BUILD_TESTS BUILD_PERF_TESTS BUILD_EXAMPLES BUILD_JAVA
        WITH_OPENCL WITH_IPP WITH_LAPACK WITH_EIGEN WITH_ITT WITH_PROTOBUF WITH_FLATBUFFERS)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
add_subdirectory("${OPENCV_SOURCE}" opencv)
if(WIN32)
    target_compile_definitions(opencv_geometry PRIVATE _USE_MATH_DEFINES)
endif()
add_executable(futacha_orb_test OrbTest.cpp ../FutachaOrb.cpp)
add_library(futacha_orb_validation SHARED ../FutachaOrb.cpp)
foreach(target futacha_orb_test futacha_orb_validation)
    target_link_libraries(${target} PRIVATE opencv_geometry opencv_features opencv_imgproc)
    target_include_directories(${target} PRIVATE ..
        "${OPENCV_SOURCE}/modules/core/include" "${OPENCV_SOURCE}/modules/features/include"
        "${OPENCV_SOURCE}/modules/geometry/include" "${OPENCV_SOURCE}/modules/imgproc/include"
        "${OPENCV_SOURCE}/modules/flann/include" "${CMAKE_BINARY_DIR}")
endforeach()
enable_testing()
add_test(NAME orb_contract COMMAND futacha_orb_test)
