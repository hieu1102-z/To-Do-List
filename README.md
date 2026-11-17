# Cấu trúc dự án To Do List

Cấu trúc thư mục được dựng theo mục 5 và 5.1 của `../docs/Tuan_3.docx`, đối chiếu với `../docs/Tuần 1.docx`, `../docs/TodoList_DB_v2.dbml` và `../docs/Thiet-ke-chia-se-theo-danh-muc.md`.

Đã triển khai trang chủ và đăng nhập theo [thiết kế Figma](https://www.figma.com/design/6abAD9XNzVCXCh4Mx4sBT3/to-do-app), bằng XML Layout và ViewBinding. Các phần chưa triển khai vẫn có `.gitkeep` để Git lưu cấu trúc.

## Chạy ứng dụng

- Mở thư mục `App` bằng Android Studio và chạy module `app`. Launcher mở thẳng trang chủ, không yêu cầu tài khoản hoặc kết nối mạng.
- Cấu hình Firebase: đặt `google-services.json` tại `app/google-services.json`, đúng package `com.finalexam.to_do_list`, và bật Email/Password trong Firebase Authentication. Gradle đọc cấu hình nếu tệp tồn tại; tệp này được bỏ qua bởi Git. Không có cấu hình thì offline vẫn hoạt động và đăng nhập báo chưa sẵn sàng.
- Kiểm tra code và cơ sở dữ liệu: `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --max-workers=2`. Database test chạy bằng JUnit/Robolectric với SQLite native trên JVM, không khởi tạo Activity hoặc mở emulator. Người dùng tự kiểm tra giao diện.

Schema DBML v2 được kiểm tra bằng build APK, lint và 19 kiểm thử JVM: schema thực tế đối chiếu DBML; PK/FK/CHECK/default/index; partial unique index; CASCADE; routing metadata; migration và rollback; CRUD và dữ liệu khách qua lần mở lại. Không chạy kiểm thử giao diện/emulator cho thay đổi này. Chưa thử đăng nhập bằng tài khoản Firebase thật. Báo cáo test: `app/build/reports/tests/testDebugUnitTest/index.html`; báo cáo lint: `app/build/reports/lint-results-debug.html`.

## Chức năng đã triển khai

- Trang chủ: ngày hiện tại, tiến độ thực tế, Hôm nay / 7 ngày tới / Quá hạn / Đã xong, tìm kiếm, chọn ngày và lọc danh mục.
- Công việc offline: tạo, sửa, xóa có xác nhận, hoàn thành/bỏ hoàn thành, tiêu đề, mô tả, danh mục, mức ưu tiên và hạn ngày/giờ. Dữ liệu khởi tạo trống; không tự chèn công việc mẫu trong Figma.
- Đăng nhập email/mật khẩu bằng Firebase Authentication, hiện/ẩn mật khẩu, kiểm tra dữ liệu, trạng thái tải/lỗi, đặt lại mật khẩu qua email, đăng xuất và quay lại dùng offline. SDK quản lý phiên, ứng dụng không lưu mật khẩu.
- Trang chủ và các thao tác offline không bị chặn bởi đăng nhập. Điểm vào chia sẻ tại Danh mục hoặc Cá nhân yêu cầu đăng nhập. Chưa triển khai backend hay thao tác chia sẻ thực tế.

## Chế độ khách

`AppDatabase` version 2 dùng đầy đủ 10 Entity Kotlin theo `TodoList_DB_v2.dbml`; Room 2.8.4 sinh mã qua KSP2. DB khách vẫn có tên `guest-workspace.db` nhưng dùng cùng schema chuẩn, không còn bảng `guest_tasks` sau khi migration thành công. `GuestTaskRepository` xử lý dữ liệu trên luồng nền; trang chủ nhận projection `HomeTask`, không nhận Entity trực tiếp.

10 bảng: `users`, `categories`, `category_members`, `category_invitations`, `tasks`, `task_participants`, `subtasks`, `task_reminders`, `sync_queue`, `sync_state`. Tên cột, kiểu SQLite, NULL/default, PK/FK và index theo DBML. UID/UUID là String; thời gian và phiên bản là Long; Boolean lưu thành INTEGER 0/1.

DB khách có một UID ổn định dạng `local:<uuid>`, email/email_key rỗng, tên Khách và email chưa xác minh. UID này chỉ là định danh cục bộ trong DB khách, không phải tài khoản Firebase. Bốn danh mục mặc định có UUID và membership ACTIVE/OWNER tương ứng. Đây là ngoại lệ định danh đã được thống nhất và ghi trong ghi chú `users.uid` của DBML.

Migration 1 → 2 chạy trong transaction: giữ nguyên UUID công việc, nội dung, hạn, ưu tiên, trạng thái, createdAt/updatedAt; chuyển danh mục từ tên sang FK UUID; bổ sung mặc định cho các cột mới. Sau khi kiểm tra số dòng và FK mới xóa bảng cũ. Dữ liệu không hợp lệ làm rollback, không xóa/reset DB hoặc âm thầm bỏ dòng. Không dùng destructive migration.

### CHECK và partial unique index

Room chưa mô hình hóa đầy đủ các CHECK và WHERE index trong Entity. `SchemaSql` là DDL bổ sung chung cho tạo mới và migration. Khi tạo DB mới, callback thay các bảng Room vừa tạo và còn rỗng bằng DDL đầy đủ CHECK; không sửa mã Room sinh tự động. Khi nâng cấp, migration dùng cùng DDL.

Hai partial index `uq_one_active_owner` và `uq_pending_invite` được cài trong `onOpen`, sau bước Room xác thực schema, trước khi bootstrap/đọc ghi ứng dụng. Không khai báo chúng thành unique index toàn phần trong Entity. Migration tương lai phải gọi `removePartialIndexesForMigration` trước khi Room xác thực; callback sẽ cài lại chúng sau đó. Schema JSON trong `app/schemas` mô tả phần Room quản lý; DDL trong `SchemaSql` bổ sung CHECK và partial index. Test đối chiếu DB thực tế trực tiếp với DBML, gồm mở lại và migration.

- Công việc khách chỉ thuộc không gian trên thiết bị, không tạo Firebase user, không đưa vào outbox và không gửi UID cục bộ hoặc dữ liệu khách lên mạng. Hai bảng sync hiện tồn tại để giữ schema nhưng Repository khách không ghi vào chúng.
- Đăng nhập/đăng xuất giữ nguyên dữ liệu offline. Chưa tự chuyển chủ sở hữu, nhập hoặc đồng bộ công việc khách vào tài khoản. Khi bổ sung chia sẻ cần thiết kế bước chuyển rõ ràng, giữ UUID và xác nhận của người dùng.
- Công việc chưa đặt hạn hiển thị ở Hôm nay. 7 ngày tới gồm hôm nay đến hết ngày thứ sáu tiếp theo theo múi giờ thiết bị. Quá hạn so sánh thời điểm hạn với thời gian hiện tại, bao gồm việc trễ giờ ngay trong hôm nay.
- Chức năng tài khoản/chia sẻ trực tuyến sau này tiếp tục dùng UID/UUID, phân quyền và giao thức trong docs. Đăng nhập chỉ là điều kiện cần; backend vẫn phải kiểm tra quyền và email xác minh khi nhận lời mời.

Công việc con, lặp lại, alarm/thông báo hệ thống, quản lý danh mục tùy chỉnh, đăng ký tài khoản, backend chia sẻ và đồng bộ chưa thuộc lần triển khai hai màn hình này. Nút chuông hiện xem danh sách việc có hạn trên thiết bị, chưa lập lịch thông báo.

## Cây thư mục theo tài liệu

```text
App/
├── app/src/main/
│   ├── java/com/finalexam/to_do_list/
│   │   ├── model/
│   │   │   ├── local/
│   │   │   │   ├── dao/
│   │   │   │   ├── entity/
│   │   │   │   └── relation/
│   │   │   ├── remote/
│   │   │   └── repository/
│   │   ├── controller/
│   │   │   ├── auth/
│   │   │   ├── home/
│   │   │   ├── task/
│   │   │   ├── list/
│   │   │   ├── search/
│   │   │   ├── calendar/
│   │   │   ├── notification/
│   │   │   ├── profile/
│   │   │   └── sharing/
│   │   ├── view/
│   │   │   ├── adapter/
│   │   │   └── widget/
│   │   ├── receiver/
│   │   ├── worker/
│   │   └── util/
│   └── res/
│       ├── layout/
│       ├── drawable/
│       ├── values/
│       └── menu/
└── functions/
    └── src/
```

Cây trên thể hiện các thư mục theo tài liệu; các tệp và thư mục có sẵn trong dự án vẫn được giữ nguyên.

## Trách nhiệm

| Thư mục | Nội dung dự kiến khi triển khai code |
| --- | --- |
| `model/local/dao` | Các DAO truy vấn Room theo UID/UUID. |
| `model/local/entity` | Các Entity tương ứng 10 bảng trong DBML v2. |
| `model/local/relation` | Quan hệ dữ liệu Room, ví dụ `TaskWithDetails`. |
| `model/local` | `AppDatabase`. |
| `model/remote` | Firebase Authentication, đọc Firestore listener/snapshot và gửi Cloud Functions callable. |
| `model/repository` | Truy cập dữ liệu, quy tắc nghiệp vụ, quyền và phối hợp đồng bộ. |
| `controller` | Activity/Fragment điều khiển các màn hình theo từng nhóm chức năng. |
| `controller/sharing` | Chia sẻ danh sách, thành viên và lời mời trong ứng dụng. |
| `view/adapter` | Adapter hiển thị công việc, danh sách và thành viên. |
| `view/widget` | Custom View, ví dụ hiển thị mức ưu tiên. |
| `receiver` | Nhận alarm nhắc việc và sự kiện khởi động để khôi phục lịch. |
| `worker` | Đồng bộ/thử lại; tổng hợp quá hạn là tùy chọn. |
| `util` | Ngày giờ, phiên đăng nhập và lên lịch nhắc nhở. |
| `res/layout` | Layout XML của màn hình và từng phần tử danh sách. |
| `res/drawable` | Tài nguyên hình ảnh và drawable. |
| `res/values` | Chuỗi, màu và theme. |
| `res/menu` | Tài nguyên menu. |
| `functions/src` | Backend callable: `index.ts`, `mutations.ts`, `invitations.ts`, `authorization.ts`. |

## Ánh xạ vào dự án hiện có

- Tài liệu dùng package minh họa `com.example.todolist`; dự án hiện có dùng namespace/applicationId `com.finalexam.to_do_list`. Cấu trúc được đặt dưới package hiện có.
- `functions/src` nằm tại gốc `App`, ngoài module Android, đúng như tài liệu. `firestore.rules` dự kiến nằm tại gốc `App` khi triển khai backend; hiện chưa tạo.
- Hai màn hình mới dùng XML và ViewBinding. Các thư viện Compose và `ui/theme` từ dự án khởi tạo vẫn được giữ cho các màn hình về sau; `MainActivity` hiện mở `HomeActivity`.
- Danh sách công việc được dựng từ dữ liệu Room vào vùng cuộn; RecyclerView có thể bổ sung khi triển khai danh sách lớn/phân trang.
- Mẫu Figma có đăng nhập Google và nhãn email/tên đăng nhập; bản triển khai theo yêu cầu docs chỉ xác thực email/mật khẩu, thay lựa chọn Google bằng “Tiếp tục dùng offline”.
