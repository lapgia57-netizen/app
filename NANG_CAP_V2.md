# Hồ Sơ Y Đức v2.1 — mục tiêu 50.000 bản ghi / tài khoản

Ứng dụng lưu cache bằng SQLite và đồng bộ theo trang/lô, nên danh sách 50.000 bản ghi không bị dựng thành 50.000 view. Tải lần đầu vẫn cần đồng bộ dữ liệu từ Firebase; những lần mở sau hiển thị trang đầu từ cache trước rồi đồng bộ nền. Thời gian đồng bộ phụ thuộc mạng, kích thước nội dung và giới hạn Firebase của dự án.

## Điểm nghẽn của bản 1.0 và cách xử lý

| # | Bản 1.0 | Bản 2.1 |
|---|---------|---------|
| 1 | **Mỗi lần lưu** tải lại TOÀN BỘ dữ liệu từ Firebase (50k bản có thể chiếm nhiều MB) để so trùng | Bộ nhớ đệm SQLite trong máy (`RecordDb`). Lưu chỉ cần 1 request nhỏ (hoặc không cần nếu vừa đồng bộ < 60 giây) |
| 2 | Mở app / sau mỗi thao tác tải lại toàn bộ + parse JSON **trên luồng chính** | Mở app hiển thị ngay từ cache, đồng bộ nền; mọi I/O và parse chạy trên IO/Default |
| 3 | `RecyclerView` đặt trong `ScrollView` → **50k item bị tạo view cùng lúc**, không tái sử dụng | `RecyclerView` tự cuộn, phân trang keyset 100 dòng/lần (cuộn gần cuối mới tải thêm) |
| 4 | Tìm kiếm: lọc 50k bản trên luồng chính mỗi phím gõ | Tìm trên SQLite (nền), chờ ngừng gõ 250 ms |
| 5 | Chống trùng: Levenshtein O(n·m) với cả 50k bản | Chỉ quét bản cùng dải độ dài → lọc bằng chữ ký ký tự 64-bit → Myers bit-parallel. **Kết quả tương đương bản cũ** |
| 6 | Khôi phục: mỗi bản gọi `saveText` → mỗi bản lại tải cả DB (O(n²) mạng) | Đồng bộ 1 lần, lọc trùng song song nhiều luồng, ghi lô 300 bản/request |
| 7 | Hex bằng `"%02x".format` từng byte; AES băm lại mật khẩu cho từng bản | Bảng tra hex; khoá AES tính 1 lần |
| 8 | Không có timeout mạng; mất mạng làm app crash hoặc bị đăng xuất oan | Có timeout, lỗi mạng trả về thông báo; offline vẫn xem được dữ liệu |

Tải lần đầu (máy mới / cài lại): 19 phân vùng khoá chạy song song 4 luồng, 1000 bản/trang; danh sách hiện ngay khi những trang đầu về. Các lần sau chỉ lấy bản mới.

## Hash cả thời gian vào bộ mã

Giữ nguyên `hashedKey = md5(nội dung)` làm khoá (để chống trùng không phụ thuộc giờ), `sha`, `sha512` như cũ, và **thêm 3 trường**:

- `timeSha256` — SHA-256 của thời gian (`savedAt` + toàn bộ giờ các múi giờ đã chọn, sắp xếp theo mã múi giờ)
- `sealSha256` — SHA-256 của (nội dung + thời gian)
- `sealSha512` — SHA-512 của (nội dung + thời gian)

Sửa nội dung **hoặc** sửa bất kỳ mốc giờ nào đều làm `seal` lệch. Trong danh sách, bản ghi mới hiện "🔒 hash thời gian hợp lệ", nếu bị sửa hiện "⚠️ KHÔNG khớp". Bản ghi cũ (chưa có seal) không hiện nhãn và không bị ảnh hưởng. `savedAt` và các múi giờ nay dùng chung một mốc thời gian (bản cũ lấy hai lần `Date()` khác nhau).

> Lưu ý: seal phát hiện sửa đổi **vô tình** hoặc lệch dữ liệu; không phải chữ ký mật mã (ai biết công thức vẫn có thể tính lại hash cho dữ liệu đã sửa). Muốn chống giả mạo thật sự cần HMAC với khoá bí mật — có thể bổ sung nếu bạn cần.

## Việc bạn cần làm (1 lần, rất nên làm)

Trong Firebase Console → Realtime Database → Rules, giữ quyền truy cập theo UID và thêm chỉ mục `savedAt` bên trong `users/$uid/data`. Tệp `firebase-rules-indexOn.json` có mẫu rules đầy đủ tương ứng:

```
{
  "rules": {
    "users": {
      "$uid": {
        ".read": "auth != null && auth.uid === $uid",
        ".write": "auth != null && auth.uid === $uid",
        "data": {
          ".indexOn": ["savedAt"]
        }
      }
    }
  }
}
```
Chỉ áp dụng mẫu này nếu cấu trúc rules hiện tại của bạn đúng như trên; nếu có các nhánh/quyền khác, hãy gộp `.indexOn` vào rules hiện có thay vì ghi đè chúng. Ứng dụng chỉ cho phép đăng nhập, không tạo tài khoản. Tài khoản cần được cấp riêng qua Firebase Authentication; quy tắc `.read`/`.write` giới hạn mỗi người dùng vào dữ liệu thuộc UID của chính họ.

Nếu chưa thêm, app vẫn chạy đúng: tự chuyển sang đối chiếu danh sách khoá (~1,75 MB cho 50k bản), chậm hơn một chút.

## Thay đổi hành vi cần biết

- **Đăng xuất = xoá cache trong máy** (dữ liệu vẫn nằm trên Firebase). Đổi tài khoản cũng xoá cache cũ.
- Thêm nút **Đồng bộ** để đối chiếu thủ công (bắt cả bản đã bị xoá ở thiết bị khác). Tự đối chiếu mỗi 24 giờ.
- Phần đầu màn hình (nhập liệu, nút) thu gọn khi cuộn xuống để danh sách chiếm toàn màn hình.
- **Định dạng backup giữ nguyên**: backup của bản 1.0 khôi phục được vào 2.0 và ngược lại. Khi khôi phục, bản ghi vẫn nhận thời gian/seal mới (như hành vi cũ).
- Lọc trùng khi khôi phục: bản y hệt (md5) bị loại ngay; bản >68% giống cũng bị loại như trước. Khôi phục 50k bản "mới hoàn toàn" vào tài khoản đã đầy dữ liệu là tác vụ nặng nhất (chạy nền, có tiến độ; khoảng 167 request ghi theo lô).

## Tệp thay đổi

Mới: `HashUtils.kt`, `Dedup.kt`, `Record.kt`, `RecordDb.kt`, `SyncManager.kt`.
Sửa: `FirebaseClient.kt`, `RecordManager.kt`, `CryptoUtils.kt`, `Prefs.kt`, `MainActivity.kt`, `RecordsAdapter.kt`, `ProcessTextActivity.kt` (một dòng thông báo), `activity_main.xml`, `item_record.xml`, `app/build.gradle` (v2.1, thêm coordinatorlayout).
Build như cũ qua GitHub Actions (`HUONG_DAN_BUILD_APK.md`).
