# Hướng dẫn lấy file APK qua GitHub Actions (không cần cài phần mềm)

## Bước 1: Tạo tài khoản & repo GitHub (nếu chưa có)
1. Vào https://github.com và tạo tài khoản miễn phí (nếu chưa có).
2. Bấm nút **"+"** góc trên phải → **New repository**.
3. Đặt tên bất kỳ, ví dụ `hoso-yduc-app`. Chọn **Public** hoặc **Private** đều được. Bấm **Create repository**.

## Bước 2: Tải toàn bộ project này lên repo
Cách dễ nhất — không cần dùng lệnh git:
1. Giải nén file zip mình gửi ra một thư mục trên máy.
2. Trong trang repo vừa tạo trên GitHub, bấm **"Add file" → "Upload files"**.
3. Kéo thả **toàn bộ nội dung** thư mục đã giải nén vào (bao gồm cả thư mục `.github`, `app`, các file `build.gradle`, `settings.gradle`, `gradle.properties`).
   ⚠️ Lưu ý: GitHub ẩn thư mục bắt đầu bằng dấu chấm (`.github`) trên một số trình duyệt khi kéo-thả cả thư mục — nếu bị thiếu, hãy vào tạo thủ công đường dẫn `.github/workflows/build-apk.yml` và dán đúng nội dung file đó vào (dùng nút "Create new file" và gõ cả đường dẫn vào ô tên file).
4. Bấm **Commit changes**.

## Bước 3: Xem GitHub tự động build
1. Sau khi tải lên xong, vào tab **Actions** ở đầu trang repo.
2. Bạn sẽ thấy một quy trình tên **"Build APK"** đang chạy (hoặc đã chạy xong) — chờ khoảng 3-5 phút.
3. Khi thấy dấu ✅ màu xanh, bấm vào lần chạy đó.
4. Kéo xuống mục **Artifacts**, bấm tải **"HoSoYDuc-debug-apk"** — đây chính là file `.apk` đã build sẵn.

## Bước 4: Cài lên điện thoại Android
1. Chuyển file `.apk` vào điện thoại (qua Google Drive, Zalo gửi cho chính mình, cáp USB...).
2. Mở file đó trên điện thoại. Nếu Android chặn "Cài từ nguồn không xác định", vào **Cài đặt → Cho phép cài đặt ứng dụng này**.
3. Cài đặt xong, mở app **Hồ Sơ Y Đức**, đăng nhập/đăng ký tài khoản Firebase.
4. Vào phần cài đặt trong app để chọn múi giờ muốn ghi lại.
5. Ra bất kỳ app nào khác (Chrome, Ghi chú...), bôi đen một đoạn văn bản → chọn **"Lưu vào Hồ Sơ Y Đức"** trong menu hiện ra.

## Lưu ý quan trọng
- Đây là bản **debug APK** (chưa ký chính thức để đăng Google Play) — chỉ dùng để cài thủ công (sideload) trên điện thoại của bạn, hoàn toàn bình thường và an toàn để tự dùng.
- Mỗi lần bạn sửa code và bấm "Commit" lại trên GitHub, quy trình Actions sẽ tự chạy lại và tạo bản APK mới trong tab Actions.
- Nếu muốn phát hành chính thức (ký APK, đăng Play Store), sẽ cần thêm bước tạo keystore ký ứng dụng — có thể hỏi thêm khi cần.
