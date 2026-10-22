# AUTH-003 — Báo cáo hoàn thiện khôi phục mật khẩu

Ngày: 2026-10-22

## Phạm vi đã rà soát

Đã đối chiếu source hiện tại và `AUTH_AUDIT_REPORT.md`. Phát hiện reset trong audit vẫn còn: code cũ chỉ gửi email, không có route callback và không có màn hình nhập mật khẩu mới. Các thay đổi login/session của AUTH-002 được giữ nguyên; không sửa luồng Gallery, Backup, Album, Editor hoặc điều hướng đăng nhập thông thường.

Supabase Kotlin SDK được Gradle resolve là 3.0.3. Source artifact xác nhận `resetPasswordForEmail(email, redirectUrl)`, `parseSessionFromFragment`, `retrieveUser`, `importSession` và `updateUser { password = ... }`. Callback implicit dùng fragment URL và session có trường `type`; code chỉ chấp nhận loại `recovery`, xác minh token với Supabase trước khi import session. Không phụ thuộc thứ tự query/fragment token; token, mật khẩu và URI callback không được log/hiển thị. SDK log bị tắt vì parser 3.0.3 có debug log fragment.

## File thay đổi

- `app/src/main/java/com/example/th6/data/AuthRepository.kt` — gửi reset với redirect cụ thể; phân loại lỗi mạng/server; xác minh và import recovery session; cập nhật password chỉ trong recovery session; xóa session cục bộ sau khi cập nhật thành công.
- `app/src/main/java/com/example/th6/data/SupabaseManager.kt` — tắt log SDK để parser không ghi callback fragment chứa token.
- `app/src/main/java/com/example/th6/viewmodels/PasswordRecoveryViewModel.kt` — điều phối request/recovery/update và chặn request đồng thời qua vòng đời tái tạo Activity.
- `app/src/main/java/com/example/th6/activities/ForgotPasswordActivity.java` — validate định dạng email, loading/khóa nút, thông báo chung sau khi API chấp nhận request.
- `app/src/main/java/com/example/th6/activities/ResetPasswordActivity.java` — nhận callback, xác minh recovery session, nhập/xác nhận password, xử lý link sai/hết hạn và yêu cầu đăng nhập lại sau cập nhật.
- `app/src/main/AndroidManifest.xml` — khai báo custom scheme callback `com.example.th6://auth-callback`.
- `app/src/main/res/layout/activity_reset_password.xml` — form password mới/xác nhận và nút hiện/ẩn.

## Cấu hình Supabase cần xác nhận

Không có quyền truy cập Dashboard trong task này, nên chưa thay đổi hoặc xác minh cấu hình backend.

1. Bật Email provider với email/password.
2. Trong Auth → URL Configuration, thêm `com.example.th6://auth-callback` vào Redirect URLs allowlist. Đặt Site URL thành website đích hợp lệ hiện có; custom scheme là redirect URL ứng dụng, không nhất thiết là Site URL.
3. Kiểm tra recovery email template dùng Supabase confirmation URL để giữ redirect `redirect_to` (thường `{{ .ConfirmationURL }}`), không hardcode URL cũ. Cấu hình SMTP/provider hoạt động.
4. Xác nhận chính sách mật khẩu và thời hạn/one-time use của recovery link theo dự án.

## Kiểm thử

| Ca | Kết quả | Ghi chú |
|---|---|---|
| 1. Email hợp lệ, gửi yêu cầu | BLOCKED | Luồng API và validate được kiểm tra bằng source/build; không gửi request tài khoản thật. |
| 2. Email được gửi thực tế | BLOCKED | Không có inbox/tài khoản kiểm thử hoặc quyền Dashboard/SMTP. API success không chứng minh delivery. |
| 3. Mở link email | BLOCKED | Cần email recovery thật. |
| 4. Ứng dụng nhận callback | BLOCKED | Manifest và callback parser có trong source; không có thiết bị/ADB hoặc link thật để runtime test. |
| 5. Link sai/hết hạn | BLOCKED | Nhánh lỗi được triển khai, chưa có link thật để xác minh backend. |
| 6. Password xác nhận không trùng | BLOCKED | Điều kiện được kiểm tra trong code; chưa chạy tương tác UI. |
| 7. Cập nhật password thành công | BLOCKED | Cần recovery session/email thật. |
| 8. Đăng nhập password mới | BLOCKED | Cần tài khoản đã reset. |
| 9. Password cũ thất bại | BLOCKED | Cần thực hiện sau reset thật và xác minh phản hồi backend. |
| 10. Mất mạng/lỗi Supabase giữa chừng | BLOCKED | Nhánh thông báo lỗi đã rà source; chưa giả lập runtime/network. |
| Build debug | PASS | `./gradlew.bat app:assembleDebug` — BUILD SUCCESSFUL. |

Kiểm tra thiết bị cũng bị BLOCKED vì `adb` không có trong PATH. Do đó không đánh dấu PASS các ca runtime chỉ dựa trên kiểm tra code.
