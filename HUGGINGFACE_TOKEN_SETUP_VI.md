# ALAD-Mobile — cấu hình Hugging Face Access Token

## Cấu hình
1. Mở Hugging Face: https://huggingface.co/settings/tokens
2. Tạo token với quyền tối thiểu cần thiết; không chia sẻ token cho người khác.
3. Trong ALAD-Mobile > Cài đặt > Hugging Face, giữ URL mặc định:
   `https://huggingface.co/spaces/abidlabs/music-separation`
4. Dán token vào ô `Hugging Face Access Token`. Ô token được che mặc định.
5. Khởi động lại phiên dịch để dịch vụ nạp lại cấu hình.

## Lưu ý xác thực
- Ứng dụng gửi token dưới dạng `Authorization: Bearer <token>` tới các yêu cầu Gradio/Space.
- Access Token không bảo đảm Space chấp nhận API; Space có thể yêu cầu quyền riêng, đăng nhập qua web, CAPTCHA, hoặc thay đổi API.
- Trong bản này token được lưu trong Android DataStore riêng của ứng dụng, nhưng **chưa mã hóa ở cấp ứng dụng**. Dùng token có quyền tối thiểu, có thể thu hồi, và không dùng token có quyền ghi/quản trị. Không đưa APK chứa token cá nhân cho người khác.
- Space hiện chưa được kiểm chứng trực tiếp trong môi trường build do kết nối mạng/DNS không khả dụng. Cần kiểm tra trên thiết bị thật; nếu Space từ chối token hoặc schema Gradio khác, ứng dụng sẽ báo lỗi và không thể xem là đã tích hợp thành công.

## Build
Mở thư mục dự án trong Android Studio và chạy `assembleDebug`. Build trong môi trường này chưa chạy được vì Gradle distribution không thể tải xuống khi DNS/mạng bị chặn.
