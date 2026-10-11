# Netflix TTS — ban rieng chay song song app Netflix goc

Bo WebView + offset. UI hien dai: the cau dang doc + nut Play to.

## Cach dung

1. Chon file `.srt` → Play.
2. Thoat sang **app Netflix goc** xem phim.
3. Dieu khien nhanh bang **nut noi mo 30%** (Play/Pause, -1s/+1s,
   gio chay `mm:ss`, volume sub, chon giong, tach/gop loa).

## Giong doc

- **Google (offline)**: khong can mang.
- **HoaiMy (online)**: can mang, preload 35 cau, cache mp3.
  Nut **Xoa cache (N MB)** de don.

## Build

Nhap repo `tuyen36/web-tts-netflix` → Actions xanh → Artifacts `app-debug`.
Cai song song duoc voi ban Web TTS (khac applicationId).
