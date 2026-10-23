# 编译好的安装包

`install.sh` 用的就是这两个文件，不需要 Java 或 Android SDK。

| 文件 | 用途 | SHA-256 |
|---|---|---|
| `tvhome.apk` | 正常版：安装后设为电视的桌面（Home） | `f2ac60d3e5399af7b31abf05be6c650f1e48d155cb168b2eaeee58d7d342f7c2` |
| `tvhome-app.apk` | 普通应用版：不声明桌面，装在原厂桌面旁边，给 TCL ak30a5 这类不允许换桌面的电视 | `aa5470442614a071c25654dba109f275ff03e77fea311f14dae0a34ba8e9c300` |

- 版本 2.6，由本仓库 `launcher/` 的源码编译（`./gradlew assembleRelease`，普通应用版加 `-PlauncherNoHome`）。
- 两个文件都用仓库里固定的 `launcher/app/fork-debug.keystore` 签名，所以你自己编译的版本可以直接覆盖安装。这是调试密钥，不能证明安装包是谁编译的，只是让各处的编译结果彼此兼容。
- 想自己编译：装好 JDK 17 和 Android SDK 34，运行 `launcher/build-and-install.sh` 或 `launcher/install-as-app.sh`。
