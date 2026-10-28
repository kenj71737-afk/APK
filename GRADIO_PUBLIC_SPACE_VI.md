# Dùng Space công khai abidlabs/music-separation trong ALAD-Mobile

Bản cập nhật này bổ sung client Gradio API để gọi Space công khai, thay vì chỉ gọi API riêng `/separate` của Docker Space.

## Cấu hình

Trong Cài đặt của ALAD-Mobile, nhập một trong hai URL sau:

- `https://huggingface.co/spaces/abidlabs/music-separation`
- `https://abidlabs-music-separation.hf.space`

Client tự chuyển URL trang Hugging Face sang hostname runtime `.hf.space`, đọc `/config` để tìm hàm có đầu vào âm thanh và ít nhất hai đầu ra âm thanh, tải tệp âm thanh lên Gradio, gọi API, rồi tải hai kết quả về. Theo giao diện Space, đầu ra đầu tiên được xem là `Vocals`; đầu ra thứ hai là nhạc nền/phần còn lại.

## Cách kiểm tra

1. Mở Space công khai và xác nhận giao diện có thể chạy một tệp âm thanh mẫu.
2. Nhập URL ở trên vào ALAD-Mobile, lưu cài đặt và khởi động lại phiên dịch.
3. Phát video ngắn, chờ trạng thái `AI đang tách lời thoại…` rồi kiểm tra cả giọng Việt và nhạc nền.
4. Nếu lỗi, xem thông báo trạng thái của ứng dụng. Lỗi có thể do API Space thay đổi, Space đang ngủ/quá tải, hoặc đầu ra API không giống cấu trúc mà client hỗ trợ.

## Giới hạn

- Đây là tích hợp API Gradio thực, không phải giả lập; tuy nhiên chưa xác minh được endpoint thực tế từ môi trường build này và chưa chạy thử trên điện thoại.
- Space công khai có thể giới hạn tốc độ, ngủ hoặc thay đổi API bất cứ lúc nào.
- Pipeline gửi các đoạn âm thanh khoảng 4 giây; vì thế không phải dịch tức thời dưới một giây. Nếu thời gian xử lý dài hơn độ dài đoạn, độ trễ sẽ tăng.
- Kết quả thứ hai được giả định là phần nhạc/âm thanh không có giọng theo giao diện của Space. Nếu thứ tự đầu ra khác, cần điều chỉnh ánh xạ sau khi kiểm tra API schema thực tế.
