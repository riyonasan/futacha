#include "FutachaOrb.h"
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <stdexcept>
#include <iostream>
#include <vector>

static void check(bool ok, const char *message) { if (!ok) throw std::runtime_error(message); }
static std::vector<int8_t> extract(const cv::Mat &image) {
    std::vector<int8_t> result(40016);
    int size = futacha_orb_extract(reinterpret_cast<const int8_t *>(image.data), image.cols, image.rows, result.data(), int(result.size()));
    check(size >= 16, "extraction failed");
    result.resize(size);
    return result;
}
static int match(const std::vector<int8_t> &a, const std::vector<int8_t> &b) {
    return futacha_orb_match(a.data(), int(a.size()), b.data(), int(b.size()));
}
int main() {
    cv::setNumThreads(1);
    cv::Mat image(160, 160, CV_8UC1), other(160, 160, CV_8UC1);
    cv::RNG rng(1234); rng.fill(image, cv::RNG::UNIFORM, 0, 256); rng.fill(other, cv::RNG::UNIFORM, 0, 256);
    cv::GaussianBlur(image, image, cv::Size(3, 3), .5);
    auto a = extract(image), b = extract(other), blank = extract(cv::Mat::zeros(160, 160, CV_8UC1));
    check(match(a, a) == 1, "same thumbnail must match");
    auto overlay = image.clone();
    cv::rectangle(overlay, cv::Rect(45, 45, 65, 45), cv::Scalar(255), -1);
    auto modified = extract(overlay);
    check(match(a, modified) == 1 && match(modified, a) == 1, "geometric overlay must match both ways");
    cv::Mat scaled; cv::resize(overlay, scaled, cv::Size(144, 144));
    check(match(a, extract(scaled)) == 1, "resized overlay must match");
    check(match(a, b) == 0 && match(a, blank) == 0, "different and textureless images must not match");
    auto invalid = a; invalid[0] = 0;
    check(match(invalid, a) == -1, "invalid version must fail");
    invalid = a; invalid.pop_back();
    check(match(invalid, a) == -1, "truncated payload must fail");
    invalid = a; invalid[12] = -1; invalid[13] = -1;
    check(match(invalid, a) == -1, "oversized feature count must fail");
    invalid = a; invalid[18] = -64; invalid[19] = 127;
    check(match(invalid, a) == -1, "non-finite coordinates must fail");
    invalid = a; invalid[4] = 0; invalid[5] = 0; invalid[6] = 0; invalid[7] = 0;
    check(match(invalid, a) == -1, "invalid dimensions must fail");
    check(futacha_orb_extract(nullptr, 1, 1, nullptr, 0) == -1, "invalid extraction must fail");
    std::cout << "ORB contract passed: identity, overlay, resize, negatives, malformed payloads\n";
}
