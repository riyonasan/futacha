# ふたったー風カタログのORB画像NG（Android）

スレタイを長押ししてメニューを開き、「ORB画像NGに追加」を選びます。
画像の長押しは従来の拡大設定に従います。登録時に「この板のみ」を外すと、
ふたったー風モードで開く全ての板のカタログに適用します。
カタログのメニューの「ORB画像NGの管理」で登録を削除できます。

初期設定では、登録がある板の判定中の画像を表示せず、一致したスレを一覧から除きます。
「ORB画像NGの管理」内の「ORB判定前に画像を表示する」をONにすると、
登録の読み込み後は画像を先に表示し、NGと判定した時点でスレを除きます。
NG画像が一時的に見えるほか、判定前のスレを開く・拡大することもできます。
このモードではORBを1件ずつ処理し、通常の3並列より操作中の負荷を抑えます。
全件の判定完了は遅くなる場合があります。実機のカクつきの軽減量は未確認です。
確定したNG判定と登録元URL、判定・登録読み込みの失敗は表示を許可しません。
通信・デコード・照合に失敗した画像も伏せ、カタログを更新すると再試行します。
画像がないスレには適用しません。既存の文字NG・検索の後、表示件数の上限の前に除外します。
「非表示・NG」画面には除外理由を表示します。

## 判定と保存

- `/cat/` から `/thumb/` に置き換えたサムネイルを、拡大せずに解析します。
  ふたばのHTTP/HTTPS設定は既存の画像読み込みと同じものを使います。
- 既存のOpenCVブリッジを利用します。追加のモデルや外部判定サービスは使いません。
- ORBは1000点、edge/patch=15、FAST=10。双方向Hamming距離の比率0.75を通る相互対応を使います。
  RANSACの誤差3px・最大5000回・信頼度0.995で、8点以上、内点率50%以上、
  両画像の内点の凸包面積8%以上を必要とします。
- 1画像に重ねた図形・文字や多少の拡縮を想定しています。特徴の少ない画像は登録できません。
  共通する顔などの部分を持つ別画像まで除外する場合もあります。
  別の元画像を使う攻撃には、その画像の追加登録が必要です。
- 最大64件。特徴量、画像URL、メモ、板の適用範囲をアプリ内部の
  `files/ftutter-orb-ng-v1.json` に保存します。更新はAndroidのAtomicFileを使います。
  元スレが落ちても特徴量は残ります。元の画像ファイルは保存しません。
  同じ保存先のストアをプロセス内で共有し、画面再作成中の保存と次の編集も直列化します。
- 候補の特徴量はカタログ画面を管理するholderのメモリーに最大256件保持します。
  ダウンロードは最大3並列、各5秒、2MiB、デコード上限約400万画素です。
  JSONの検証・書き出し準備と特徴抽出・照合はUIスレッドの外で行います。
- 空いた処理枠は、その時点で画面内にある未判定画像を優先します。
  スクロールで進行中の取得を取り消さず、遅い1件が後続の固定グループを止めない共有待ち行列を使います。
- 判定用に取得したサムネイルも、最大8MiB／256件のメモリーLRUに保持して表示へ渡します。
  初期設定では判定がCLEARの場合だけ表示し、表示のための再取得を減らします。
  元バイト列が消えても同じ表示キャッシュキーを使い、デコード済み画像を再利用できます。
  表示キャッシュも消えた画像は従来の表示用読み込みに戻ります。ディスク保存は追加しません。
- CLEAR／HIDDENの確定判定は最大4096件のメモリーLRUに保持し、スレ・板一覧との往復でも再利用します。
  画像URL（HTTP／HTTPSを含む）と適用中のルール内容が完全一致する場合に限ります。
  登録の読み込み前は表示せず、登録変更後に古いCLEARを使いません。
  途中離脱までに確定した結果も残し、ERRORは保存せず再入場・更新で再試行します。
  アプリ終了やカタログholderの再作成後までの永続化は行いません。
- Android DebugではOpenCVのcore／imgproc／features／geometry／flannと`FutachaOrb.cpp`に`-O2`を追加します。
  アプリのDebug署名・IDはそのままです。OpenCVは既存の追尾機能と共有されます。

この最初の実装はAndroidのふたったー風カタログのみが対象です。
従来のpHashの設定・距離スライダーとは別管理で、既存のNGデータは変更しません。
スレッド内、他モード、iOS、デスクトップの判定は変わりません。
ORB登録は既存の設定エクスポート／インポートには含まれません。

## 検証

- `shared/src/commonTest/kotlin/ui/ftutter/FtutterOrbNgTest.kt`:
  保存・再読み込み・削除、保存失敗／キャンセル、破損データ、板の範囲、
  未判定画像の表示抑制、HTTP失敗・キャッシュ、走査キャンセル。
  画面内の優先順位の変更、遅い画像による後続の待ち、取得画像の再利用とLRU容量制限も検証します。
  256件超の再入場・並び替え、途中離脱、ルール変更、ERROR再試行、確定判定のLRUも検証します。
- `shared/src/nativeInterop/tracking/tests`:
  合成画像で同一画像、図形重ね、拡縮、無関係／無特徴の画像、破損した特徴量を検証します。
  実際の掲示板画像はリポジトリーに含めません。

ネイティブテストは、本体と同じOpenCVコミット
`40738fb16ceddb5fb3fea747585f7ce6abb0605b` を用意し、次で実行できます。

```sh
cmake -S shared/src/nativeInterop/tracking/tests -B build/orb-tests -DOPENCV_SOURCE=/absolute/path/to/opencv -DCMAKE_BUILD_TYPE=Release
cmake --build build/orb-tests --target futacha_orb_test
ctest --test-dir build/orb-tests --output-on-failure
```

個別テストの成功はAPKのビルド・実機の動作確認を意味しません。

再入場対策・native最適化・メニュー表示の変更をまとめたAndroid Debugビルドを確認しました。
ORB関連のAndroidホストテスト17件とAndroidアプリ単体テスト97件が成功しています。
4種類のCPU向けの`compile_commands.json`で、対象OpenCVモジュールとORBソースの
最終有効な最適化オプションが`-O2`であることを確認しました。
APKの署名・16KB配置・ORBのJNIエクスポートも確認済みです。
利用者による20a42c3版のPixel 9a／Android 17での試用では、実用に足る速度への改善が確認されています。
ただし処理中のカクつきは残っています。古い端末の速度と、共有OpenCVを使う追尾機能の実機動作は未確認です。

## Android試用APKのビルド

READMEのAndroid Debugビルド手順を使います。公開ソースで欠落していた
`tools/prepare-android-tracking.py` と `tools/prepare-voicevox.py` を補完しています。
Python 3.11以上、README指定のJDK 17／Gradle用JDK 21、SDK 37、
NDK 29.0.14206865、CMake 3.22.1が必要です。
初回は公式配布元から依存を取得するためネット接続が必要です。

```powershell
.\gradlew.bat :app-android:assembleDebug '-Pfutacha.orbTest=true'
```

`app-android/build/outputs/apk/debug/app-android-debug.apk` に生成します。
このオプションを付けたDebug版は「ふたちゃ ORB検証」、アプリIDは
`com.valoser.futacha.orbtest` になります。既存アプリと併存でき、データも別です。
オプションを省略したビルドとRelease版のアプリIDは変更しません。
Debug署名の試用APKであり、正式リリース用の品質・配布ゲートを通ったものではありません。
既存の`-Pfutacha.splitApks=true`も付けるとCPU別APKを生成します。
Pixel 9aには`app-android-arm64-v8a-debug.apk`を使用します。

補完した準備処理はOpenCVの上記コミット、VOICEVOX CORE 0.17.0と
公式推奨のVOICEVOX ONNX Runtime 1.23.2を使い、アーカイブのSHA-256を固定します。
生成物はGit管理外の`build/`に置きます。Android用のみを補完しており、
欠落しているデスクトップホストやRelease用シンボル最適化ツールは対象外です。
デスクトップホストがない公開スナップショットでもAndroidを構成できるようにしています。
`testDebugUnitTest`には欠落した`tools/tests/test_android_symbols.py`を呼ぶフックも
残っています。0件のテスト成功を検証済みと扱わないため、このスナップショットでの
Androidユニットテストは`-x :app-android:testNativeSymbolPackaging`を付けて実行し、
そのシンボル梱包テストは未実施として扱います。

準備処理の境界チェックは次で実行できます。

```powershell
python -m unittest discover -s tools/tests -p test_native_dependencies.py -v
```

表示優先の切り替えは、未判定・一致・失敗・ルール変更・キャッシュ再利用と、1件ずつの処理をテスト対象に追加しています。

## フォークの正式配布（13.0-orb.1）

このフォークでは`main`に統合したコミットからAndroid arm64 APKを作り、
GitHubの通常のReleaseとして公開します。本家のリリースではありません。
利用者の指定により、検証版から設定・ORB登録を保った上書き更新を可能にするため、
従来と同じDebug構成・署名・アプリID `com.valoser.futacha.orbtest` を継続します。
表示名は「ふたちゃ ORB」、バージョンは`13.0-orb.1`、versionCodeは227です。

```powershell
.\gradlew.bat :app-android:assembleDebug '-Pfutacha.orbStable=true' '-Pfutacha.splitApks=true'
```

`app-android/build/outputs/apk/debug/app-android-arm64-v8a-debug.apk` を配布します。
インストール済みの「ふたちゃ ORB検証」をアンインストールせず更新してください。
署名鍵は引き続きローカルで保持し、リポジトリー・Releaseには含めません。
このフォーク配布の検証範囲はAndroidのビルド・ORBホストテスト・アプリ単体テスト・
APK署名と16KB配置の検査です。新しい先表示モードの実機操作・性能、他端末、
共有OpenCVを使う追尾の実機動作は未確認です。
本家の全OS向け`verifyReleaseReadiness`合格や、R8を使うRelease構成の検証を意味しません。

## カタログ本文の取得文字数（次回配布分）

カタログ取得時の既定文字数指定を16から256に増やしています。
NGタイトル・ピックアップタイトルは、画面の行数や文字数制限で省略する前の、
取得済みの本文を判定します。表示の行数設定は変更しません。
共通の取得設定を使う通常モードにも適用され、元から256指定の互換モードはそのままです。
既存の16指定Cookieは次のネットワーク取得時に256指定へ更新します。
スレごとの追加取得は行わず、256文字より後まで含む全文取得を保証するものではありません。
