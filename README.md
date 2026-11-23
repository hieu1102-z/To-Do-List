# Cấu trúc dự án To Do List

## Đồng bộ và chia sẻ

Trang chủ hiển thị công việc Local và công việc tài khoản. Từ menu danh mục Local, chọn **Đồng bộ qua tài khoản** và xác nhận tài khoản đích. Danh mục, công việc và công việc con giữ nguyên ID. Tài khoản đích trở thành OWNER. Nếu danh mục bị trùng tên, chọn tên khác rồi thử lại.

Danh mục đang chuyển xuất hiện trong vùng tài khoản ở chế độ chỉ xem. Dữ liệu nguồn nằm trong DB khách đến khi máy chủ xác nhận các lệnh và quyền OWNER, sau đó app xóa dữ liệu nguồn. WorkManager tiếp tục khi có mạng và đúng tài khoản đăng nhập. Khi đăng xuất, danh mục ẩn như các danh mục tài khoản khác. Các bản sao tạo từ phiên bản cũ vẫn là danh mục riêng.

Trong **Chia sẻ & thành viên**, OWNER mời theo email và chọn quyền. Người nhận vào **Cá nhân → Lời mời danh mục** bằng email đã xác minh. EDITOR có thể xóa vào thùng rác và khôi phục. Chỉ OWNER được xóa vĩnh viễn. EDITOR và VIEWER có thể chọn **Rời danh mục**. Đồng bộ thay đổi offline cần phiên đúng tài khoản và kết nối trở lại.

Cấu hình lặp của task tài khoản được lưu và đồng bộ. Việc tự sinh kỳ tiếp theo hiện có trong không gian Local.

Backend Node.js, kiểm thử Firebase và cấu hình Rules/indexes thuộc repo BE riêng. Firestore `(default)` vẫn ở `asia-east1`. Android gọi API tại `https://todolist-be-zeg3.onrender.com` theo `WORKSPACE_API_BASE_URL` trong `gradle.properties` ở gốc repo App.

Để phát triển chức năng đăng nhập, đồng bộ và chia sẻ, đặt `google-services.json` của Firebase project `to-do-list-d22e9` tại `app/google-services.json`, rồi Sync Gradle trong Android Studio hoặc build từ gốc repo App. Lần build thông thường tự lấy URL chung của nhóm.

~~~powershell
.\gradlew.bat :app:assembleDebug
~~~

URL là địa chỉ HTTPS gốc. Android tự thêm `/v1/workspace`. Khi cần thử một BE khác, truyền `-PWORKSPACE_API_BASE_URL=https://<may-chu-khac>` vào lệnh Gradle. Có thể truyền `-PWORKSPACE_API_BASE_URL=` để thử bản build chỉ dùng Local.

Chạy kiểm thử Android bằng Gradle.

~~~powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
~~~

Các lệnh Android chỉ chạy kiểm thử code và dữ liệu. Fixture DBML của kiểm thử Room nằm trong `app/src/test/fixtures`, nên không cần checkout BE hoặc thư mục tài liệu chung để build và kiểm thử.

## Cửa sổ sửa công việc

`EditTaskDialog` tái sử dụng cùng XML của form thêm công việc: ô tiêu đề/mô tả, ngày hạn và nhắc nhở cạnh nhau, chip ưu tiên, dropdown danh mục/lặp lại, nút Lưu thay đổi. Giữ dạng cửa sổ nổi và cuộn nội dung. Điền dữ liệu hiện có, giữ UUID và trạng thái hoàn thành. Thay đổi nhắc nhở/lịch lặp được lưu qua repository; nếu không đổi nhắc nhở thì giữ nguyên khoảng nhắc đã lưu.

`DeleteTaskDialog` theo ảnh tham chiếu người dùng cung cấp: cửa sổ trắng bo góc, tiêu đề “Xóa công việc này?”, tên công việc thật và dòng “sẽ bị xóa. Không thể hoàn tác.”, nút Hủy nền nhạt và nút Xóa màu đỏ. Tên dài xuống dòng/cuộn, không dùng tên mẫu trong ảnh. Xác nhận xóa đưa task vào thùng rác. Chỉ nút Xóa gọi repository bằng ID công việc; khóa nút khi đang xử lý, lỗi giữ cửa sổ để thử lại. Hủy/Back/chạm ngoài trước khi xác nhận không thay đổi dữ liệu. Chỉ đóng cửa sổ cha và tải lại danh sách sau khi xóa thành công. Không mở Activity hoặc emulator trong kiểm thử.

## Cửa sổ chi tiết công việc

Chạm công việc sẽ mở dialog chi tiết có trạng thái/ưu tiên/lặp lại, hạn, nhắc nhở, danh mục, mô tả và nút hoàn thành/bỏ hoàn thành. Có thể thêm, sửa, đánh dấu và xóa mềm công việc con; dữ liệu lấy từ Room. Sửa mở form hiện có, Xóa giữ bước xác nhận.

## Giao diện thêm công việc

Nút + mở `AddTaskActivity` toàn màn hình, dựa trên frame Figma `3:11877` và các điều chỉnh: Ưu tiên dùng chip; Danh mục và Lặp lại dùng dropdown. Nút Lưu công việc cố định ở dưới, nội dung cuộn khi bàn phím mở. Hạn mặc định là 00:00 ngày tiếp theo theo múi giờ thiết bị (tính theo ngày lịch, không cộng cố định 24 giờ); luôn hiển thị ngày/giờ, không còn placeholder hoặc nút Bỏ hạn. Ưu tiên mặc định Trung bình; Danh mục luôn mặc định `to do`, kể cả khi mở từ trang chủ đang lọc danh mục; Lặp lại mặc định Không.

`to do` là lựa chọn đầu trong dropdown, biểu thị chưa phân loại. Do DBML yêu cầu `tasks.category_id NOT NULL`, repository tạo lười một vùng chứa `to do` cùng membership OWNER/ACTIVE khi lưu lần đầu; các lần sau dùng lại. Không gán công việc chưa phân loại vào Cá nhân hoặc các danh mục người dùng khác, không đổi schema hay chuyển dữ liệu cũ. Bộ lọc và form sửa nhận diện `to do` để không tự đổi phân loại khi sửa tiêu đề.

Nhắc nhở cho phép không nhắc, đúng giờ hoặc trước 5/15/30/60 phút/1 ngày; lưu vào `task_reminders` cùng transaction với công việc. Giữ khoảng nhắc khi sửa hạn, hoàn thành/bỏ hoàn thành và bật/tắt thông báo. Việc phát thông báo vẫn phụ thuộc quyền thông báo, quyền báo thức chính xác và cài đặt hệ thống hiện có. ViewModel chặn lưu lặp khi đang xử lý và giữ trạng thái yêu cầu khi xoay màn hình; SavedState giữ lựa chọn ngày/danh mục/ưu tiên/nhắc nhở.

Lặp lại hỗ trợ Không, hằng ngày, hằng tuần và hằng tháng. Khi hoàn thành việc lặp, ứng dụng giữ bản ghi đã hoàn thành và tạo kỳ tiếp theo cùng công việc con; tháng ngắn hơn sẽ giới hạn ngày về ngày cuối tháng. Lịch lặp yêu cầu có ngày hạn.

Kiểm tra thay đổi này bằng build APK, lint và test JVM (SQLite/Repository/ViewModel); không mở emulator hoặc Activity để kiểm thử giao diện.

Cấu trúc thư mục được dựng theo mục 5 và 5.1 của `../docs/Tuan_3.docx`, đối chiếu với `../docs/Tuần 1.docx`, `../docs/TodoList_DB_v2.dbml` và `../docs/Thiet-ke-chia-se-theo-danh-muc.md`.

Đã triển khai trang chủ, đăng nhập và đăng ký bằng XML Layout/ViewBinding, dùng chung phong cách giao diện của [thiết kế Figma](https://www.figma.com/design/6abAD9XNzVCXCh4Mx4sBT3/to-do-app). Các phần chưa triển khai vẫn có `.gitkeep` để Git lưu cấu trúc.

## Chạy ứng dụng

- Mở thư mục `App` bằng Android Studio và chạy module `app`. Launcher mở thẳng trang chủ, không yêu cầu tài khoản hoặc kết nối mạng.
- Cấu hình Firebase: đặt `google-services.json` tại `app/google-services.json`, đúng package `com.finalexam.to_do_list`, và bật Email/Password trong Firebase Authentication. Gradle đọc cấu hình nếu tệp tồn tại; tệp này được bỏ qua bởi Git. Không có cấu hình thì offline vẫn hoạt động và đăng nhập báo chưa sẵn sàng.
- Kiểm tra code và cơ sở dữ liệu: `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --max-workers=2`. Database test chạy bằng JUnit/Robolectric với SQLite native trên JVM, không khởi tạo Activity hoặc mở emulator. Người dùng tự kiểm tra giao diện.

## Chức năng đã triển khai

- Trang chủ: ngày hiện tại, tiến độ thực tế, Hôm nay / 7 ngày tới / Quá hạn / Đã xong, tìm kiếm, chọn ngày và lọc danh mục.
- Công việc offline trong Room: tạo, sửa, xóa mềm có xác nhận, hoàn thành/bỏ hoàn thành, tiêu đề, mô tả, danh mục, mức ưu tiên, hạn, lặp lại, nhắc nhở và công việc con. Dữ liệu khởi tạo trống; không tự chèn công việc mẫu.
- Thùng rác: xem task xóa mềm, khôi phục hoặc xóa vĩnh viễn từng task/dọn sạch. Xóa vĩnh viễn ghi thao tác `TASK/DELETE` vào `sync_queue` cùng transaction trước khi xóa task và các bản ghi con khỏi SQLite.
- Danh mục Room: thêm, đổi tên và xóa mềm; tên không trùng trong danh mục còn hoạt động và không cho xóa danh mục cuối cùng.
- Lịch, tìm kiếm/lọc/sắp xếp, hồ sơ cục bộ và trang thông báo riêng truy vấn dữ liệu lưu trên thiết bị.
- Đăng nhập email/mật khẩu bằng Firebase Authentication, hiện/ẩn mật khẩu, kiểm tra dữ liệu, trạng thái tải/lỗi, đặt lại mật khẩu qua email, đăng xuất và quay lại dùng offline. SDK quản lý phiên, ứng dụng không lưu mật khẩu.
- Đăng ký với họ tên, email, mật khẩu và xác nhận mật khẩu; gửi/gửi lại email xác minh và tải lại trạng thái xác minh từ Firebase.
- Trang chủ và các thao tác offline không bị chặn bởi đăng nhập. Điểm vào chia sẻ tại Danh mục hoặc Cá nhân yêu cầu đăng nhập.

## Đăng nhập email và hồ sơ tài khoản

- Bật Firebase Console → Authentication → Sign-in method → Email/Password. Có thể tạo tài khoản ngay từ nút Đăng ký trong màn hình đăng nhập, hoặc đăng nhập bằng tài khoản đã tồn tại. [Hướng dẫn SDK](https://firebase.google.com/docs/auth/android/password-auth).
- `FirebaseEmailAuthGateway` chỉ làm việc với Firebase Authentication; `AuthRepository` điều phối xác thực, lưu hồ sơ và hủy yêu cầu. Không lưu mật khẩu hoặc token trong Room, SavedState hay log. Firebase SDK quản lý phiên đăng nhập.
- Chỉ báo đăng nhập thành công sau khi Firebase xác thực và hồ sơ được cache vào bảng `users`. DB hồ sơ riêng có tên `account-<SHA-256 của UID>.db`, dùng schema Room v4 nhưng không bootstrap UID khách. Cập nhật hồ sơ bằng Upsert, không dùng REPLACE để tránh xóa user đang có khóa ngoại. `server_updated_at` chỉ giữ mốc máy chủ đã có, không giả lập bằng giờ thiết bị.
- Hồ sơ cache gồm UID, email, email_key chuẩn hóa, họ tên, ảnh và trạng thái xác minh email do Firebase SDK cung cấp. Cờ xác minh cache phục vụ hiển thị, không thay thế việc backend kiểm tra token khi nhận lời mời.
- ViewModel giữ trạng thái yêu cầu qua thay đổi cấu hình, chặn gửi lặp; Back/Tiếp tục offline hủy việc hoàn tất luồng đăng nhập. Nếu Firebase trả kết quả trễ sau khi hủy/đăng xuất, không phục hồi phiên đã hủy. Lỗi ghi hồ sơ không báo thành công và đăng xuất phiên vừa xác thực.
- Khi ứng dụng tiếp tục chạy, có thể cache lại danh tính mà SDK đã khôi phục mà không yêu cầu nhập mật khẩu hay chặn trang chủ bằng mạng. Đăng xuất xóa phiên Firebase, giữ cache cục bộ và toàn bộ dữ liệu khách.
- Trang chủ hiển thị dữ liệu của cả hai không gian. Chuyển danh mục Local lên tài khoản cần xác nhận rõ tài khoản đích.
- Kiểm thử chỉ chạy code/JVM với gateway giả lập và SQLite native: thành công/thất bại, validation, lỗi mạng/cấu hình, yêu cầu lặp, hủy/đăng xuất trong lúc xử lý, khôi phục phiên, profile Upsert và cách ly dữ liệu khách. Không gửi yêu cầu đăng nhập hoặc email đặt lại mật khẩu đến project thật trong test; không mở emulator/Activity. Chưa xác nhận đăng nhập bằng tài khoản thật.

## Đăng ký và xác minh email

- Đường vào: Cá nhân → Đăng nhập → Chưa có tài khoản? Đăng ký. Biểu mẫu yêu cầu họ tên không trống, email hợp lệ, mật khẩu ít nhất 6 ký tự và xác nhận trùng khớp. Chính sách mật khẩu chặt hơn do Firebase Console cấu hình vẫn được SDK kiểm tra; không cắt khoảng trắng của mật khẩu.
- Tạo tài khoản qua `createUserWithEmailAndPassword`, cập nhật displayName qua `updateProfile`, cache hồ sơ theo Firebase UID và gọi `sendEmailVerification`. Chỉ Firebase xác thực/mang mật khẩu; không thêm cột mật khẩu vào Room và không tự viết dữ liệu nghiệp vụ lên Firestore.
- Tạo tài khoản thành công giữ phiên Firebase để dùng chức năng cá nhân, không ép xác minh mới được dùng offline. Màn hình kết quả hiển thị email, nút Tôi đã xác minh, Gửi lại email xác minh và Về trang chủ. Tài khoản chưa xác minh có thể mở lại màn hình này từ Cá nhân → Xác minh email.
- Khi kiểm tra hoặc quay lại màn hình xác minh, SDK gọi `reload()` rồi `getIdToken(true)` trước khi cache trạng thái mới. Backend chia sẻ/lời mời kiểm tra email đã xác minh từ token, không tin cờ cache.
- Gửi lại email có thời gian chờ 60 giây theo UID trong tiến trình ứng dụng và tôn trọng lỗi hạn mức của Firebase. Không tạo lại tài khoản khi gửi email hoặc cập nhật họ tên thất bại; kết quả vẫn nói rõ tài khoản đã được tạo và hiển thị phần chưa thành công. Nếu cache hồ sơ thất bại sau tạo tài khoản, ứng dụng báo quay lại đăng nhập thay vì đăng ký lại.
- Back/Tiếp tục offline trong lúc tạo hủy việc nhận kết quả/phiên của yêu cầu đó. Việc hủy không xóa tài khoản đã được Firebase tạo; không tự động chạy thao tác xóa tài khoản. Mật khẩu không được lưu trong ViewModel hoặc SavedState và được xóa khỏi biểu mẫu khi hoàn tất/rời màn hình.
- Đăng ký không đổi UID của task khách. WorkManager đồng bộ dữ liệu tài khoản sau khi có phiên và kết nối.
- Kiểm thử chỉ dùng gateway giả và SQLite native trên JVM, không tạo tài khoản/gửi email thật, không khởi tạo Activity hoặc emulator. Phần đăng ký bổ sung kiểm tra validation, email trùng, lỗi mạng, kết quả tạo một phần, gửi lại/cooldown, reload, hủy/đăng xuất và bảo toàn task khách. Thư viện Fragment kéo gián tiếp được ràng buộc tối thiểu 1.8.9 để tương thích Activity Result API.

## Schema và dữ liệu khách

`AppDatabase` version 4 dùng 10 Entity Kotlin theo `TodoList_DB_v2.dbml`, bổ sung `deleted_at` cho các bản ghi hỗ trợ xóa mềm. Room 2.8.4 sinh mã qua KSP2. DB khách có tên `guest-workspace.db`, dùng schema chuẩn; `GuestTaskRepository` xử lý dữ liệu trên luồng nền. Migration 2 → 3 thêm cột xóa mềm nếu chưa có. Migration 3 → 4 thêm phụ thuộc outbox và bảng ghi lần sao chép.

10 bảng nghiệp vụ và đồng bộ: `users`, `categories`, `category_members`, `category_invitations`, `tasks`, `task_participants`, `subtasks`, `task_reminders`, `sync_queue`, `sync_state`. `local_category_transfers` lưu trạng thái chuyển danh mục qua lần mở lại. `local_category_copies` giữ metadata của các bản sao cũ. Tên cột, kiểu SQLite, NULL/default, PK/FK và index theo DBML. UID/UUID là String; thời gian và phiên bản là Long; Boolean lưu thành INTEGER 0/1.

DB khách có một UID ổn định dạng `local:<uuid>`, email/email_key rỗng, tên Khách và email chưa xác minh. UID này chỉ là định danh cục bộ trong DB khách, không phải tài khoản Firebase. Bốn danh mục mặc định có UUID và membership ACTIVE/OWNER tương ứng. Đây là ngoại lệ định danh đã được thống nhất và ghi trong ghi chú `users.uid` của DBML.

Migration 1 → 2 chạy trong transaction: giữ nguyên UUID công việc, nội dung, hạn, ưu tiên, trạng thái, createdAt/updatedAt; chuyển danh mục từ tên sang FK UUID; bổ sung mặc định cho các cột mới. Sau khi kiểm tra số dòng và FK mới xóa bảng cũ. Dữ liệu không hợp lệ làm rollback, không xóa/reset DB hoặc âm thầm bỏ dòng. Không dùng destructive migration.

### CHECK và partial unique index

Room chưa mô hình hóa đầy đủ các CHECK và WHERE index trong Entity. `SchemaSql` là DDL bổ sung chung cho tạo mới và migration. Khi tạo DB mới, callback thay các bảng Room vừa tạo và còn rỗng bằng DDL đầy đủ CHECK; không sửa mã Room sinh tự động. Khi nâng cấp, migration dùng cùng DDL.

Các partial index `uq_category_owner_name`, `uq_one_active_owner` và `uq_pending_invite` được cài trong `onOpen`, sau bước Room xác thực schema, trước khi bootstrap/đọc ghi ứng dụng. Chúng loại trừ bản ghi đã xóa mềm khi áp dụng duy nhất tên danh mục. Migration gọi `removePartialIndexesForMigration` trước khi thay đổi schema; callback cài lại chúng sau đó. Schema JSON trong `app/schemas` mô tả phần Room quản lý; DDL trong `SchemaSql` bổ sung CHECK và partial index.

- Công việc khách chỉ thuộc không gian trên thiết bị, không tạo Firebase user và không gửi UID cục bộ hoặc dữ liệu khách lên mạng. Hàng đợi khách không được gửi lên máy chủ.
- Đăng nhập và đăng xuất giữ các danh mục Local chưa được chuyển qua tài khoản.
- Công việc chưa đặt hạn hiển thị ở Hôm nay. 7 ngày tới gồm hôm nay đến hết ngày thứ sáu tiếp theo theo múi giờ thiết bị. Quá hạn so sánh thời điểm hạn với thời gian hiện tại, bao gồm việc trễ giờ ngay trong hôm nay.
- Chức năng tài khoản/chia sẻ trực tuyến sau này tiếp tục dùng UID/UUID, phân quyền và giao thức trong docs. Đăng nhập chỉ là điều kiện cần; backend vẫn phải kiểm tra quyền và email xác minh khi nhận lời mời.

Room lưu dữ liệu hiển thị và hàng đợi thay đổi. BE trên Render kiểm tra quyền và ghi Firestore. Khi nhận ACK hoặc snapshot đầy đủ, ứng dụng cập nhật cache trong Room.

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
```

Cây trên thể hiện các thư mục theo tài liệu; các tệp và thư mục có sẵn trong dự án vẫn được giữ nguyên.

## Trách nhiệm

| Thư mục | Nội dung dự kiến khi triển khai code |
| --- | --- |
| `model/local/dao` | Các DAO truy vấn Room theo UID/UUID. |
| `model/local/entity` | Các Entity tương ứng 10 bảng trong DBML v2. |
| `model/local/relation` | Quan hệ dữ liệu Room, ví dụ `TaskWithDetails`. |
| `model/local` | `AppDatabase`. |
| `model/remote` | Adapter Firebase Authentication. Luồng tài khoản gọi API HTTPS qua WorkspaceRemote. |
| `model/repository` | Truy cập Room, quy tắc nghiệp vụ cục bộ và điều phối xác thực. |
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

## Ánh xạ vào dự án hiện có

- Tài liệu dùng package minh họa `com.example.todolist`; dự án hiện có dùng namespace/applicationId `com.finalexam.to_do_list`. Cấu trúc được đặt dưới package hiện có.
- Backend triển khai từ repo BE. Bản Cloud Functions trước đây được giữ trong workspace để đối chiếu khi xác nhận chuyển đổi.
- Hai màn hình mới dùng XML và ViewBinding. Các thư viện Compose và `ui/theme` từ dự án khởi tạo vẫn được giữ cho các màn hình về sau; `MainActivity` hiện mở `HomeActivity`.
- Danh sách công việc được dựng từ dữ liệu Room vào vùng cuộn; RecyclerView có thể bổ sung khi triển khai danh sách lớn/phân trang.
- Mẫu Figma có đăng nhập Google và nhãn email/tên đăng nhập; bản triển khai theo yêu cầu docs chỉ xác thực email/mật khẩu, thay lựa chọn Google bằng “Tiếp tục dùng offline”.
