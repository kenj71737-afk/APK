# Sửa lỗi nhạc nền đã tách bị mất khi tắt tiếng gốc

## Nguyên nhân
Cờ `isOriginalMuted` trước đây được dùng đồng thời để đặt âm lượng video gốc và âm lượng nhạc nền Demucs. Khi người dùng bật Mute Original để bỏ lời thoại nước ngoài, nhạc nền đã tách cũng bị đặt về 0.

## Sửa đổi
- Khi Demucs đã trả về một đoạn tách hợp lệ, ứng dụng tắt âm thanh video gốc và phát instrumental stem riêng.
- Nút tắt tiếng gốc chỉ tắt nguồn video; không còn đặt âm lượng instrumental stem về 0.
- Thanh âm lượng gốc/nhạc nền điều khiển âm lượng stem đã tách độc lập.

## Giới hạn
Đây là sửa logic trong mã nguồn; chưa thể build hoặc kiểm thử APK trong môi trường hiện tại vì Gradle cần tải `gradle-9.3.0-bin.zip` từ `services.gradle.org`, nhưng môi trường không có kết nối DNS/Internet. Cần build và kiểm thử trên Android Studio có Internet, Space Hugging Face phải hoạt động và trả về cả `vocals_wav_base64` lẫn `instrumental_wav_base64`.


Fix3: instrumental AudioTrack uses USAGE_ASSISTANCE_ACCESSIBILITY so zeroing STREAM_MUSIC to mute the source video is less likely to silence the separated bed. This still requires device testing because Android OEM audio routing varies.
