# Materialbook integration notes

The reference project is [eepiemi/Materialbook](https://github.com/eepiemi/Materialbook), a public fork of ycngmn/Nobook. Its README documents Material You colors applied to the Facebook page and media downloading/copying capabilities. The repository page identifies the project as GPL-3.0 licensed.

The QBooK port keeps the implementation isolated: `QBookMaterialYouBridge.kt` exposes resolved theme colors, `QBookDownloadBridge.kt` saves base64 media through MediaStore or the legacy Downloads path, and the two raw JavaScript resources add page recoloring and a floating media-download action. Existing QBooK WebView listeners, bridges, blockers, offline code, and DownloadManager behavior remain in place.
