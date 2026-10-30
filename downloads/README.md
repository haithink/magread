# Android 安装包

[下载刊读 v0.2.2（Debug 测试版）](https://github.com/haithink/magread/raw/refs/heads/main/downloads/magread-v0.2.2-debug.apk)

| 项目 | 值 |
|---|---|
| 文件 | `magread-v0.2.2-debug.apk` |
| 版本 | 0.2.2（versionCode 4） |
| 应用 ID | `com.yichen.kandu` |
| 构建类型 | Debug |
| 最低系统 | Android 8.0（API 26） |
| 大小 | 24,490,460 字节（约 23.4 MiB） |
| SHA-256 | `fde4fb1b9ce6152748026c7f84d56a4f96baeb6e82403ea9ff2aa8f1b426cce6` |

此文件复制自本地已有的 `app/build/outputs/apk/debug/app-debug.apk`，未重新构建。
Debug 包使用调试签名；后续切换为不同签名的 Release 包时，Android 不能直接覆盖安装，
应先导出需要保留的数据，再卸载旧包并安装新包。

## 更新安装包

1. 更新 `app/build.gradle` 中的 `versionCode` 和 `versionName`，重新构建 APK。
2. 将新 APK 复制到本目录，文件名包含实际版本号及构建类型。
3. 更新本文件与根目录 README 中的下载链接、版本、大小和 SHA-256。
4. 提交并推送 APK 与文档；GitHub 下载链接随后提供更新后的文件。

可使用 PowerShell 的 `Get-FileHash -Algorithm SHA256 文件路径` 计算校验值。
