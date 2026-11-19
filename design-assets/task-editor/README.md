# Tài nguyên màn hình thêm công việc

Nguồn: Figma `6abAD9XNzVCXCh4Mx4sBT3`, frame `3:11877`, đọc ngày 19/11/2026.

- `close.svg`: node `3:12701`, kích thước gốc 22 × 22.
- `bell.svg`: node `3:12704`, kích thước gốc 18 × 18.

Hai SVG gốc được chuyển trực tiếp sang Android VectorDrawable `ic_editor_close.xml` và `ic_editor_bell.xml`, giữ nguyên viewBox, đường nét, màu, stroke và kích thước. Không dùng URL Figma tạm thời khi ứng dụng chạy. Thanh trạng thái và thanh điều hướng dùng thành phần hệ thống Android.

Tham chiếu bố cục: lề ngang 20dp, thanh tiêu đề 60dp, ô nhập 44dp (mô tả 58dp), bo góc 12dp, chip cao tối thiểu 30dp/bo 8dp, khoảng chip 6dp, nút lưu 50dp. Sử dụng font sans của ứng dụng; chip tự xuống dòng khi màn hình hẹp hoặc tăng cỡ chữ. Màu thương hiệu riêng của màn hình là #4F6BED theo frame.
