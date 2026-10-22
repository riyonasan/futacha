#include "FutachaOrb.h"
#include <opencv2/core.hpp>
#include <opencv2/features.hpp>
#include <opencv2/geometry.hpp>
#include <opencv2/imgproc.hpp>
#include <cmath>
#include <cstring>
#include <vector>

namespace {
constexpr int max_points = 1000, header = 16, record = 40;
uint32_t read32(const int8_t *p) {
    uint32_t v = 0;
    for (int i = 0; i < 4; ++i) v |= uint32_t(uint8_t(p[i])) << (8 * i);
    return v;
}
void write32(int8_t *p, uint32_t v) {
    for (int i = 0; i < 4; ++i) p[i] = int8_t(v >> (8 * i));
}
float read_float(const int8_t *p) { auto v = read32(p); float f; std::memcpy(&f, &v, 4); return f; }
void write_float(int8_t *p, float f) { uint32_t v; std::memcpy(&v, &f, 4); write32(p, v); }
struct Features {
    std::vector<cv::Point2f> points;
    cv::Mat descriptors;
    double area;
};
bool decode(const int8_t *p, int size, Features &f) {
    if (!p || size < header || std::memcmp(p, "ORB1", 4)) return false;
    const auto w = read32(p + 4), h = read32(p + 8), n = read32(p + 12);
    if (!w || !h || uint64_t(w) * h > 4194304 || n > max_points || size != header + record * int(n)) return false;
    f.area = double(w) * h;
    f.descriptors = cv::Mat(int(n), 32, CV_8UC1);
    for (uint32_t i = 0; i < n; ++i) {
        const auto *r = p + header + record * i;
        float x = read_float(r), y = read_float(r + 4);
        if (!std::isfinite(x) || !std::isfinite(y) || x < 0 || y < 0 || x >= w || y >= h) return false;
        f.points.emplace_back(x, y);
        std::memcpy(f.descriptors.ptr(int(i)), r + 8, 32);
    }
    return true;
}
std::vector<int> nearest(const cv::Mat &a, const cv::Mat &b) {
    std::vector<std::vector<cv::DMatch>> pairs;
    cv::BFMatcher(cv::NORM_HAMMING).knnMatch(a, b, pairs, 2);
    std::vector<int> result(a.rows, -1);
    for (const auto &pair : pairs)
        if (pair.size() == 2 && pair[0].distance < .75f * pair[1].distance)
            result[pair[0].queryIdx] = pair[0].trainIdx;
    return result;
}
double coverage(const std::vector<cv::Point2f> &p, double area) {
    std::vector<cv::Point2f> hull;
    cv::convexHull(p, hull);
    return std::abs(cv::contourArea(hull)) / area;
}
}

int futacha_orb_extract(const int8_t *gray, int w, int h, int8_t *output, int capacity) {
    if (!gray || !output || w <= 0 || h <= 0 || int64_t(w) * h > 4194304 || capacity < header + max_points * record) return -1;
    try {
        std::vector<cv::KeyPoint> points;
        cv::Mat descriptors;
        auto orb = cv::ORB::create(1000, 1.2f, 8, 15, 0, 2, cv::ORB::HARRIS_SCORE, 15, 10);
        orb->detectAndCompute(cv::Mat(h, w, CV_8UC1, const_cast<int8_t *>(gray)), cv::noArray(), points, descriptors);
        if (points.size() > max_points) return -1;
        std::memcpy(output, "ORB1", 4);
        write32(output + 4, w); write32(output + 8, h); write32(output + 12, uint32_t(points.size()));
        for (size_t i = 0; i < points.size(); ++i) {
            auto *r = output + header + record * i;
            write_float(r, points[i].pt.x); write_float(r + 4, points[i].pt.y);
            std::memcpy(r + 8, descriptors.ptr(int(i)), 32);
        }
        return header + record * int(points.size());
    } catch (...) { return -1; }
}

int futacha_orb_match(const int8_t *left, int left_size, const int8_t *right, int right_size) {
    try {
        Features a, b;
        if (!decode(left, left_size, a) || !decode(right, right_size, b)) return -1;
        if (a.points.size() < 8 || b.points.size() < 8) return 0;
        auto ab = nearest(a.descriptors, b.descriptors), ba = nearest(b.descriptors, a.descriptors);
        std::vector<cv::Point2f> from, to;
        for (size_t i = 0; i < ab.size(); ++i) if (ab[i] >= 0 && ba[ab[i]] == int(i)) {
            from.push_back(a.points[i]); to.push_back(b.points[ab[i]]);
        }
        if (from.size() < 8) return 0;
        cv::Mat mask;
        // Use the independently tested thumbnail criteria; no full-image rescaling.
        cv::findHomography(from, to, cv::RANSAC, 3., mask, 5000, .995);
        std::vector<cv::Point2f> in_a, in_b;
        for (int i = 0; i < mask.rows; ++i) if (mask.at<uint8_t>(i)) {
            in_a.push_back(from[i]); in_b.push_back(to[i]);
        }
        if (in_a.size() < 8 || in_a.size() * 2 < from.size()) return 0;
        return coverage(in_a, a.area) >= .08 && coverage(in_b, b.area) >= .08 ? 1 : 0;
    } catch (...) { return -1; }
}
