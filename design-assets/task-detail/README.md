# Chi tiết công việc

Tham chiếu Figma: file `6abAD9XNzVCXCh4Mx4sBT3`, frame `3:11961`.

Các SVG tải từ context Figma được chuyển trực tiếp sang VectorDrawable `ic_detail_*`, giữ nguyên đường nét, màu, stroke và kích thước: back 22dp; calendar/bell/folder/person 17dp; check 20dp. Không dùng URL tạm thời trong ứng dụng.

Bố cục được điều chỉnh theo yêu cầu: cửa sổ dialog giữa màn hình, tối đa rộng 560dp và có lề hai bên 24dp. Nội dung cuộn tối đa nửa chiều cao màn hình; header và nút hoàn thành nằm ngoài vùng cuộn. Tái sử dụng màu surface, phong cách sans, nút chính và ChipFlowLayout của dự án. Bỏ thanh trạng thái/gesture giả của Figma vì đây là cửa sổ trong ứng dụng Android. Bỏ toàn bộ nhóm công việc con cùng các nút thêm/xóa; không thay đổi bảng hoặc dữ liệu subtasks.

Thông tin hiển thị lấy từ projection Room: deadline, nhắc nhở thật và trạng thái bật/tắt, danh mục, chu kỳ lặp, người tham gia có membership ACTIVE. Không đưa dữ liệu mẫu của Figma vào công việc. Hoàn thành/bỏ hoàn thành dùng repository hiện có; Sửa mở form chỉnh sửa hiện tại; Xóa vẫn yêu cầu xác nhận.
