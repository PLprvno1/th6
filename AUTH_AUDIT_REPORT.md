# AUTH-001 — Audit đăng nhập và đặt lại mật khẩu sau migration Supabase

**Phạm vi:** chỉ audit source/config; không sửa mã nguồn, không gọi Supabase hoặc thay đổi Dashboard/dữ liệu.  
**Ngày audit:** 2026-10-22.  
**Trạng thái:** chưa kiểm thử trên thiết bị với tài khoản/email thật; build debug thành công.

## Kết luận nhanh

- Đăng nhập email/mật khẩu gọi Supabase Auth qua `AuthRepository.signIn()`. Có bắt exception và callback thành công/thất bại; UI bật/tắt loading và khóa nút trong lúc request.
- Splash kiểm tra session/user cục bộ rồi chọn Login hoặc Main. Đăng xuất gọi Supabase nhưng bỏ qua kết quả boolean, sau đó UI luôn điều hướng về Login.
- Quên mật khẩu chỉ gọi `resetPasswordForEmail(email)`. Theo tài liệu Supabase, đây chỉ là bước gửi email; phải xử lý callback/recovery rồi gọi `updateUser { password = ... }` để đặt mật khẩu mới. Source không có các bước sau này.
- Manifest không khai báo deep link cho Auth và source không gọi `handleDeeplinks`; không có Activity nhập mật khẩu mới. Vì vậy reset end-to-end chưa được triển khai trong app.
- Có nhánh đăng ký điều hướng vào Main dù Auth trả về userId null. Khi Supabase yêu cầu xác nhận email, session có thể chưa được tạo; cần xác minh theo cấu hình thực tế.

## 1. File đã đọc và vai trò

| File | Vai trò |
|---|---|
| `app/src/main/java/com/example/th6/data/SupabaseManager.kt` | Singleton lazy tạo SupabaseClient; URL/key và cài Auth, Postgrest, Storage. |
| `app/src/main/java/com/example/th6/data/AuthRepository.kt` | Đăng ký/đăng nhập/reset email, kiểm tra session, đọc user và đăng xuất; chuyển API suspend thành callback cho Java UI. |
| `app/src/main/java/com/example/th6/activities/LoginActivity.java` | Validate trường đăng nhập, gọi repository, loading, thông báo và điều hướng tới Main/Register/Forgot. |
| `app/src/main/java/com/example/th6/activities/ForgotPasswordActivity.java` | Validate email, gọi yêu cầu reset, hiển thị kết quả và quay về Login. |
| `app/src/main/java/com/example/th6/activities/RegisterActivity.java` | Đăng ký Supabase, tạo profile PostgREST và điều hướng sau đăng ký. Được đọc vì flow và session phụ thuộc đăng ký. |
| `app/src/main/java/com/example/th6/activities/SplashActivity.java` | Kiểm tra session lúc mở app, điều hướng Login/Main. |
| `app/src/main/java/com/example/th6/fragments/ProfileFragment.java` | Đọc user Supabase, dữ liệu hồ sơ và thực hiện đăng xuất từ Profile. |
| `app/src/main/java/com/example/th6/activities/SettingsActivity.java` | Có luồng đăng xuất thứ hai từ Settings. |
| `app/src/main/java/com/example/th6/MainActivity.java` | Host 5 tab; dùng Auth user ID để lên lịch backup, nhưng không tự chặn truy cập khi không có session. |
| `app/src/main/AndroidManifest.xml` | Khai báo quyền Internet, Splash launcher, auth Activities và các Activity nội bộ; kiểm tra intent filters/deep links. |
| `app/src/main/res/layout/activity_login.xml` | Trường email/password, nút đăng nhập và các điểm chuyển sang Forgot/Register. |
| `app/src/main/res/layout/activity_forgot_password.xml` | Form gửi email reset; không có trường mật khẩu mới. |
| `app/src/main/res/layout/activity_register.xml` | Form đăng ký và xác nhận mật khẩu. |
| `app/src/main/res/layout/activity_splash.xml` | Giao diện splash. |
| `app/src/main/res/values/strings.xml` | Chuỗi UI liên quan auth. |
| `app/build.gradle.kts`, `gradle/libs.versions.toml` | Dependency Supabase Kotlin SDK (BOM 3.0.3 và auth-kt), Android/Kotlin plugin. |
| Các kết quả tìm kiếm toàn `app/src/main` | Kiểm tra tham chiếu Firebase/Auth cũ, ImgBB, deeplink, callback và các điểm gọi Auth. Không thấy Firebase Auth trong source/dependencies hiện tại; ImgBB chỉ còn ở chuỗi/comment không thuộc luồng đăng nhập. |

## 2. Sơ đồ đăng nhập hiện tại

```text
SplashActivity (đợi 1.2 giây)
  -> AuthRepository.checkIsLoggedInAsync()
       -> Auth.currentSessionOrNull() != null && currentUserOrNull() != null
  -> MainActivity nếu true; LoginActivity nếu false

LoginActivity
  -> kiểm tra email/mật khẩu không rỗng
  -> khóa nút + hiện progress
  -> AuthRepository.signInAsync()
       -> Supabase Auth signInWith(Email)
  -> callback thành công: tắt progress, Toast, MainActivity (clear task/back stack)
  -> callback lỗi: tắt progress, Toast lỗi, ở lại Login
```

Forgot Password và Register được mở bằng Intent nội bộ từ Login. Register quay lại Login bằng `finish()`. Đăng nhập thành công dùng `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`, nên không quay Back về Login.

## 3. Sơ đồ khôi phục/đặt lại hiện tại

```text
LoginActivity -> ForgotPasswordActivity
  -> validate chỉ kiểm tra email không rỗng
  -> AuthRepository.resetPasswordAsync(email)
       -> auth.resetPasswordForEmail(email)
  -> API không ném exception: thông báo “Đã gửi email...” và finish về Login
  -> exception: tắt loading, hiển thị lỗi

Email link -> [không có route/handler trong app]
  -> [không có xử lý callback/token]
  -> [không có form nhập mật khẩu mới]
  -> [không gọi auth.updateUser { password = ... }]
```

Supabase xác định `resetPasswordForEmail()` chỉ gửi link; sau redirect cần xử lý Auth flow rồi thu mật khẩu mới và gọi `updateUser()`. SDK hỗ trợ tham số redirect URL tùy chọn; code hiện không truyền tham số này. [Supabase Kotlin: reset password](https://supabase.com/docs/reference/kotlin/auth-resetpasswordforemail) · [Supabase password auth guide](https://supabase.com/docs/guides/auth/passwords)

## 4. Phát hiện đã xác nhận

### P1 — Reset mật khẩu chưa hoàn chỉnh

**Trạng thái:** Đã xác nhận từ source.  
**Bằng chứng:** `AuthRepository.kt:70-76` chỉ gọi `auth.resetPasswordForEmail(email)`; toàn bộ source/manifest không có `updateUser`, `handleDeeplinks`, Auth recovery handler, deep link VIEW filter hoặc Activity nhập mật khẩu mới. `ForgotPasswordActivity.java:42-48` báo kết quả và đóng màn hình ngay sau API response.

**Tác động:** app khởi chạy email-reset request nhưng không có app-side flow được tìm thấy để hoàn tất cập nhật mật khẩu mới. Có thể email dẫn tới trang web/đích cấu hình ngoài, nhưng không thể xác nhận từ source.

### P1 — Đăng ký có thể đưa người dùng chưa có session vào Main

**Trạng thái:** Nhánh điều hướng đã xác nhận; điều kiện xảy ra phụ thuộc cấu hình Supabase.  
**Bằng chứng:** `RegisterActivity.java:73-110`: bất kỳ callback đăng ký thành công nào cũng điều hướng Main. Nếu userId null, code vẫn báo thành công rồi gọi `navigateToMain()`; nếu tạo profile lỗi cũng vẫn đi Main. Supabase có thể không tạo session khi bật xác nhận email; điều này phải kiểm tra trên project thực tế.

**Tác động:** Main và các màn hình dữ liệu có thể mở trong trạng thái chưa authenticated; các truy vấn cloud có thể lỗi/rỗng. Nên chỉ vào Main khi xác nhận session/user hiện tại hợp lệ; nếu email confirmation cần thiết, hiển thị hướng dẫn và quay về Login/chờ xác minh.

### P2 — Mật khẩu bị trim trước khi gửi

**Trạng thái:** Đã xác nhận.  
**Bằng chứng:** `LoginActivity.java:40-41` trim cả email và password. `RegisterActivity.java:39-41` trim password và confirmation trước khi đăng ký.

**Tác động:** Mật khẩu hợp lệ có khoảng trắng ở đầu/cuối sẽ bị đổi trước khi gửi; đăng nhập có thể thất bại hoặc tài khoản đăng ký không dùng đúng chuỗi người dùng nhập. Chỉ email nên được chuẩn hóa bằng trim; password nên giữ nguyên.

### P2 — Đăng xuất điều hướng kể cả khi Supabase signOut báo thất bại

**Trạng thái:** Đã xác nhận.  
**Bằng chứng:** `AuthRepository.kt:106-114` trả `false` nếu signOut ném exception. Nhưng `signOutAsync()` tại `194-202` bỏ qua giá trị trả về và luôn chạy callback. `ProfileFragment.java:257-273` và `SettingsActivity.java:51-64` luôn chuyển Login trong callback.

**Tác động:** UI báo trạng thái đăng xuất bằng điều hướng dù repository không xác nhận sign-out thành công. Session thực tế sau lỗi cần kiểm thử với SDK/network.

### P2 — Không kiểm tra định dạng email ở Login/Forgot Password

**Trạng thái:** Đã xác nhận.  
**Bằng chứng:** `LoginActivity.java:43-55` và `ForgotPasswordActivity.java:33-38` chỉ chặn chuỗi rỗng; layout đặt inputType email nhưng đây chỉ là gợi ý bàn phím, không thay thế validate.

**Tác động:** Email sai định dạng vẫn tạo request và chỉ hiện lỗi backend. Không phải bypass auth; chủ yếu ảnh hưởng trải nghiệm.

## 5. Cần kiểm thử (chưa xác nhận runtime)

- Session Supabase có được lưu/khôi phục giữa cold start, process death và nâng cấp app với cấu hình SDK 3.0.3 cụ thể. Source gọi `currentSessionOrNull()`, nhưng không có instrumentation/device run trong audit này.
- SDK tự refresh token ra sao khi app nền lâu; cách singleton lazy được khởi tạo có tương tác đúng với Android lifecycle không.
- Login sai mật khẩu, email chưa đăng ký, mất mạng, timeout/server error và trạng thái UI khi Activity bị hủy trong lúc callback đang chạy.
- Loading đã được tắt ở callback success/error trong Login/Forgot. Chưa có nhánh timeout riêng; nếu callback không về thì UI có thể tiếp tục loading.
- Nút bị disable đồng bộ khi bắt đầu request, nên thao tác nhấn lặp thông thường bị chặn; cần kiểm thử tương tác nhanh/rotation để xác nhận.
- `checkIsLoggedIn()` bắt mọi exception và xem như logged out. Cần kiểm thử offline để phân biệt session chưa đăng nhập với trạng thái SDK chưa tải/refresh thất bại.
- Sau khi gọi signOut lỗi, kiểm tra xem session có còn trong SDK và dữ liệu user có tiếp tục truy cập được không.
- Thông báo “đã gửi email” chỉ chứng minh Auth API không trả exception. Supabase không tiết lộ nếu email không tương ứng user để chống user enumeration; delivery/SMTP cần xác minh ngoài app. [Supabase password auth guide](https://supabase.com/docs/guides/auth/passwords)

## 6. Cần cấu hình bên ngoài (Supabase Dashboard/email)

Không thể xác minh các mục sau chỉ từ source:

1. Email/password provider được bật.
2. **Confirm email** bật/tắt; điều này ảnh hưởng việc đăng ký có tạo session và có thể làm lộ nhánh Register→Main với userId null.
3. Site URL, Redirect URLs và email template cho reset password. Nếu muốn mở app, redirect phải khớp URI/deep link đã triển khai và được allowlist.
4. Email SMTP/provider, template, rate limits và khả năng gửi email thực tế.
5. Các policy bảo mật/auth đang áp dụng trên project.

Không có service-role key trong code; client dùng key publishable hardcoded trong `SupabaseManager.kt:10-11`. Đây là key client-side theo mục đích SDK, nhưng quyền dữ liệu phải được bảo vệ bởi RLS/policy server; không đưa service-role key vào APK.

## 7. Ưu tiên lỗi

| Ưu tiên | Phát hiện |
|---|---|
| P1 | Reset chỉ gửi email, thiếu callback/recovery và cập nhật password mới. |
| P1 | Register điều hướng Main dù userId/session có thể null (tác động phụ thuộc Confirm email). |
| P2 | Trim mật khẩu làm biến đổi password người dùng nhập. |
| P2 | UI đăng xuất ngay cả khi signOut trả false. |
| P2 | Login/Forgot không validate format email trước request. |

## 8. Kế hoạch sửa đề xuất (task nhỏ, theo phụ thuộc)

1. **AUTH-002a — Chốt hợp đồng recovery và Dashboard:** quyết định deep link scheme/host hoặc HTTPS app link, xác nhận Email provider/Confirm email, Site URL/Redirect URLs và email template. Không thay đổi Dashboard trong audit này.
2. **AUTH-002b — Bổ sung recovery route:** khai báo intent filter/route cho callback, xử lý link một lần và chuyển trạng thái Auth recovery theo supabase-kt 3.0.3.
3. **AUTH-002c — Màn hình đặt mật khẩu mới:** validate password/confirm, gọi Supabase Auth `updateUser { password = ... }`, chỉ báo thành công khi backend xác nhận; hỗ trợ lỗi/hết hạn link.
4. **AUTH-002d — Sửa login/register input và điều hướng:** không trim password; validate email; chỉ vào Main sau khi user/session được xác nhận; xử lý email confirmation rõ ràng.
5. **AUTH-002e — Sửa kết quả sign-out/session:** truyền trạng thái thành công/thất bại lên UI, chỉ điều hướng khi policy logout đã được quyết định; kiểm tra guard cho Main/private screens.
6. **AUTH-002f — Kiểm thử thiết bị/backend staging:** cold start/session restore, login success/failure/offline, sign-out failure, reset link mở app, link hết hạn, cập nhật password và login lại; không dùng tài khoản production nếu chưa được phép.

## 9. Build

Đã chạy lệnh:

```text
./gradlew.bat app:assembleDebug
```

Kết quả: **BUILD SUCCESSFUL in 18s** (37 actionable tasks: 7 executed, 30 up-to-date). Đây là kiểm tra biên dịch tĩnh, không chứng minh các luồng Auth hoạt động tại runtime. Không chạy test suite và không thực hiện request/backend action.

## Tham chiếu Supabase

- [Kotlin: Send a password reset request](https://supabase.com/docs/reference/kotlin/auth-resetpasswordforemail)
- [Password-based Auth](https://supabase.com/docs/guides/auth/passwords)
- [Redirect URLs](https://supabase.com/docs/guides/auth/redirect-urls)
- [supabase-kt Auth troubleshooting: deep links](https://github.com/supabase-community/supabase-kt/blob/master/TROUBLESHOOTING.md)

