# ALAD-Mobile — dịch theo cụm nghĩa, giữ ngữ cảnh

## Thay đổi trong bản này

- Luồng hướng dẫn Gemini Live được gửi cho cả trường hợp chọn tiếng Việt chuẩn, không còn chỉ gửi khi người dùng chọn phương ngữ.
- Prompt yêu cầu dịch và bắt đầu nói ngay khi hiểu được một cụm/câu mệnh đề có nghĩa, không chờ video kết thúc.
- Prompt cũng yêu cầu dùng ngữ cảnh của các cụm trước để nối câu tiếng Việt tự nhiên, không lặp lại cụm đã nói, không tóm tắt hoặc bỏ nghĩa.
- Bản dịch hoàn tất không tự biến mất sau 4,5 giây; cụm mới sẽ thay thế khi lượt dịch tiếp theo bắt đầu, giúp người dùng còn thấy câu vừa dịch.

## Giới hạn cần biết

Đây là thay đổi hướng dẫn mô hình và giữ phụ đề. Độ trễ thực tế còn tùy API, mạng và khả năng nhận dạng âm thanh. Không thể bảo đảm tuyệt đối dịch tức thì hay đồng bộ hoàn hảo.

Tách lời thoại khỏi nhạc bằng Demucs là một pipeline riêng. Endpoint Hugging Face trong `huggingface-space/` xử lý từng tệp âm thanh và hiện chưa được nối vào luồng audio live của Android. Vì vậy, bản này KHÔNG tuyên bố đã tách được lời thoại trực tiếp trong lúc video phát.

## Build

Mở dự án trong Android Studio, đồng bộ Gradle rồi chọn Build > Build APK(s). Môi trường tạo gói không có kết nối Internet để tải Gradle nên chưa xác nhận build APK thành công.

## Sửa lỗi âm nền bị im lặng / giọng dịch không được trộn

Bản sửa này thay đổi các điểm sau:
- Không giảm âm lượng video gốc cho đến khi nhận được stem Demucs hợp lệ đầu tiên; nếu Space lỗi, âm thanh gốc không bị tắt ngay từ đầu.
- Track nhạc nền đã tách dùng `USAGE_ASSISTANT` thay vì `USAGE_MEDIA`, để việc tắt `STREAM_MUSIC` của video gốc không đồng thời làm im track nhạc nền.
- Không chờ frame giọng dịch đầu tiên mới phát nhạc nền. Hai `AudioTrack` phát độc lập để Android trộn nhạc nền và giọng dịch khi cả hai có dữ liệu.
- Gửi lời thoại đã tách đến WebSocket theo các frame 40 ms thay vì dồn cả đoạn 4 giây vào một lượt.

Vẫn cần cấu hình URL HTTPS của Hugging Face Space đang chạy Demucs. Nếu status hiển thị lỗi tách âm thanh, kiểm tra `/health` và log của Space. Bản sửa mã nguồn chưa được xác nhận build APK trong môi trường này do Gradle cần tải từ Internet.
