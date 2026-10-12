# MyImageViewer

大学の時間割など、選択した 1 枚の画像をすぐに表示する Android / Kotlin / Jetpack Compose アプリです。

- 初回は「画像を選択」から Android Photo Picker を開きます。Photo Picker が利用できない OS では AndroidX のシステムファイル選択へのフォールバックを利用します。
- 選択画像はアプリの内部ストレージへコピーされ、再起動時に自動で表示されます。元画像の URI 権限を永続化する必要はありません。
- 縦横比を維持して画面内に全体を表示します。画像や周囲の余白を長押しすると変更できます。キャンセル時は現在の画像を維持します。
- コピー・読み込みは IO スレッドで行います。新しい画像の検証が完了した時点で保存ファイルをアトミックに置換し、失敗時は以前の画像を残します。
- 保存する元ファイルは最大 50 MiB。表示用のデコードは長辺最大約 2048 px に縮小し、EXIF の向きも反映します。ズーム・複数画像管理・アニメーション再生は対象外です。
- アプリ自身のネットワーク通信や、写真ライブラリ全体へのアクセス権限はありません。アプリのデータ削除・アンインストールで内部保存画像も消えます。

## 開発・検証

AGP 9.4.1 に対応した Android Studio、JDK 25、Android SDK Platform 37.0 を使用します。Gradle Wrapper は 9.6.0、minSdk は 26 です。SDK の場所は `ANDROID_HOME` またはローカルの `local.properties` で設定します。Wrapper の実行ビットがないため、Unix では `bash gradlew` を使用できます。

```sh
bash gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --max-workers=3
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

JVM テストは保存の置換・コピー中断・不正画像・サイズ上限・保存失敗・縮小率を検証します。計装テストは実画像の保存と復元、デコード、EXIF 回転、選択ボタン、長押し、エラー表示を検証します。接続した端末で実行する場合:

```sh
bash gradlew connectedDebugAndroidTest
```

クラウドではビルド・JVM テスト・Lint と計装テスト APK のビルドを検証しています。実機上の計装テストと Photo Picker の操作は未検証です。

## Pixel 9a で確認する項目

1. 新規インストールで「画像を選択」が表示され、Photo Picker が画像のみの選択で開く。
2. 縦長・横長・スクリーンショット・回転情報付き写真を選択し、切り抜きや変形なく全体が表示される。
3. タスク終了・強制停止後に再起動しても表示される。元画像を削除しても内部コピーから表示される。
4. 表示中の画像や余白を長押しして別画像へ変更できる。選択をキャンセルすると現在の画像が残る。初回選択のキャンセルでは選択ボタンに戻る。
5. 選択画面を開いたまま画面回転・アプリのプロセス再生成を行い、選択結果が失われない。保存中の回転でも処理が継続する。
6. 大きな写真の選択中に進行表示が出て、UI が固まらない。50 MiB を超える画像、読み込み不可の画像、容量不足ではエラーを表示し、以前の画像を維持する。
7. ライト／ダークテーマ、ジェスチャー／3 ボタンナビゲーション、横画面で画像とシステムバーの表示を確認する。TalkBack の長押しアクションからも画像を変更できる。
8. オフラインで端末内画像の選択・表示・再起動後の復元ができ、不要な権限ダイアログが出ない。

## GitHub Actions による半自動リリース

通常のブランチへの Push／Pull Request では、これまでどおりデバッグビルド・JVM テスト・Lint だけを実行します。署名済み Release APK や GitHub Release は作成しません。

`v1.0.2` のようなタグへの Push では **Android Draft Release** が実行されます。JDK 25、Gradle Wrapper 9.6.0、AGP 9.4.1、SDK 37.0／Build-Tools 36.0.0 を使用し、次の順序で処理します。

1. タグが `v` + `versionName` と一致し、同じタグの Draft／公開済み Release がないことを確認します。安定版の `v数字.数字.数字` 形式だけを受け付けます。
2. `testDebugUnitTest` と `lintRelease` を実行します。失敗したら APK のビルドへ進みません。
3. `assembleRelease` で未署名 APK を生成し、アプリ ID、バージョン、デバッグ無効、ソースコミット、SHA-256 を記録します。
4. 別ジョブでダウンロードした APK のハッシュを照合し、既存のリリース鍵で署名します。署名と ZIP alignment を検証します。
5. `MyImageViewer v1.0.2` という **Draft Release** を作成し、`MyImageViewer-v1.0.2.apk` を添付します。ソースコミットと APK の SHA-256 は Release 本文に記録します。

**自動公開はしません。最後の公開操作は必ず人間が GitHub 上で行います。** タグの自動作成・変更・削除も行いません。既存 Gradle には署名設定を追加せず、SDK の `apksigner` を使います。Android Studio で使っている既存の鍵をそのまま登録できます。

### 1. 既存の鍵を Secrets に登録する

GitHub のリポジトリ画面で **Settings → Secrets and variables → Actions → New repository secret** を開き、次の 4 件を登録します。これはクラウド開発環境の Secrets ではなく、GitHub リポジトリの **Actions Secrets** です。

| Secret 名 | 登録する内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 既存のリリース用 Keystore を Base64 に変換した文字列 |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore のパスワード |
| `ANDROID_KEY_ALIAS` | Android Studio で署名時に選んでいる鍵のエイリアス |
| `ANDROID_KEY_PASSWORD` | その鍵のパスワード（Keystore と同じでも別々に登録） |

新しい Keystore は生成しないでください。公開済みアプリを更新するには、以前と同じ署名鍵が必要です。秘密鍵・パスワードを Git、Issue、PR、チャットへ貼り付けないでください。

Windows PowerShell では、実際のファイルパスに置き換えて次を実行すると、Base64 文字列を画面やファイルに出さずクリップボードへコピーできます。

```powershell
$releaseKeyPath = "C:\Keys\myimageviewer-release.jks"
[Convert]::ToBase64String([IO.File]::ReadAllBytes($releaseKeyPath)) | Set-Clipboard
```

GitHub の `ANDROID_KEYSTORE_BASE64` の値欄に貼り付け、保存します。その後、必要に応じてクリップボードを消します。

```powershell
Set-Clipboard -Value ""
```

Base64 は暗号化ではありません。鍵本体と同様に管理してください。Secrets が不足する、Base64 が壊れている、パスワードやエイリアスが違う場合はワークフローが失敗します。秘密情報を表示しないため、署名ツールの詳細エラーはログへ出しません。

**Settings → Actions → General** で Actions が許可されていることも確認します。ビルドジョブは `contents: read`、Draft 作成ジョブだけ `contents: write` を要求します。組織ポリシーで書き込みが禁止されている場合は管理者の対応が必要です。追加の PAT は不要です。タグを Push できる人は署名ワークフローも起動できるため、リリース権限を持つ人に絞るタグ Ruleset の設定を推奨します。未レビューのコードにタグを付けないでください。

### 2. アプリのバージョンを更新する

まず、このワークフローの変更を main に取り込みます。その後、リリースする変更と次のバージョン更新を通常の作業ブランチ／PR で main に取り込みます。

現在の設定は `versionName = "1.0.1"`、`versionCode = 2` です。次を `1.0.2` とする場合、`app/build.gradle.kts` を以下のように更新します。

```kotlin
versionCode = 3
versionName = "1.0.2"
```

`versionCode` は配布のたびに前回より大きくします。`versionName` と `versionCode` はこのファイルの定数として管理し、タグは `v1.0.2` のように `v` を付けます。ワークフローは正の versionCode と APK 内の値との一致を確認しますが、前回より増えているかはリリース担当者が確認してください。

### 3. タグを作成して Push する

次のコマンドは、新しいリリースを作ると決めたときだけ手元で実行します。`1.0.2` は例です。作業ツリーがクリーンで、通常 CI が成功したコミットを使ってください。

```powershell
git switch main
git pull --ff-only origin main
git status --short
git fetch origin --tags
git tag --list v1.0.2
```

`git status --short` に変更が表示された場合は先に解決します。タグ一覧に `v1.0.2` が出た場合や、GitHub Releases に同じタグの Draft／公開済み Release がある場合は止めてください。既存のタグを上書き・削除せず、新しいバージョンを決めます。

問題なければ実行します。

```powershell
git tag -a v1.0.2 -m "MyImageViewer v1.0.2"
git push origin refs/tags/v1.0.2
```

タグはこのワークフローを含むコミットに付ける必要があります。既存タグの付け直しや `--force` は使わないでください。

### 4. 結果と Draft APK を確認して公開する

1. GitHub の **Actions → Android Draft Release** を開き、対象タグの実行を選びます。
2. **Validate, test, and build release**、**Sign APK and create Draft Release** の両ジョブが成功したことを確認します。
3. ジョブの Summary のリンク、またはリポジトリの **Releases** から対象の Draft を開きます。Draft は管理権限のあるユーザーでログインして確認します。
4. 添付 `MyImageViewer-v1.0.2.apk` をダウンロードし、Pixel 9a でインストール・画像選択・再起動後の表示を確認します。本文のコミットと APK の SHA-256 も確認できます。必要に応じて、SDK の `apksigner verify --print-certs` で以前の APK と署名証明書が同じことを確認します。
5. Release の編集画面で、このバージョンの変更内容・注意事項を追記します。生成された本文はアプリ概要とビルド記録です。
6. 確認が済んでから **Publish release** をクリックします。確認中は **Save draft** のままにします。

ビルドと署名間の転送に使う **MyImageViewer-release-input** は未署名 APK とビルド記録で、1 日間だけ Actions Artifacts に保存します。配布には使用しません。鍵は runner の一時ディレクトリだけに復元し、成功・失敗時に削除します。リリース鍵・パスワードはキャッシュや Artifacts に保存しません。

### 5. 失敗したとき

- **バージョン不一致**：タグと `versionName`、APK 内の値を確認します。既存タグを移動せず、必要なら修正コミットと新バージョンを用意します。
- **テスト／Lint 失敗**：失敗ステップのログと **MyImageViewer-release-reports**（7 日間）を確認します。通常 CI の結果も確認してください。端末が必要な計装テストはこのワークフローでは実行しません。
- **Secrets／署名失敗**：4 件の Secret 名・内容、Base64、既存鍵のエイリアスとパスワードを見直します。秘密値をログへ出す変更はしないでください。修正後、Draft がまだ作成されていなければ **Re-run failed jobs** で再実行できます。転送 Artifact が期限切れなら **Re-run all jobs** を使います。
- **GitHub API 失敗**：Actions の権限、組織ポリシー、GitHub の障害状況を確認します。通信・権限エラーを「Release がない」とは扱わず停止します。
- **既存 Release がある**：Draft／公開済みのどちらでも停止します。再実行で APK を上書きしたり、二重に Release を作ったりしません。
- **Draft 作成後のアップロード失敗**：空または添付途中の Draft が残る場合があります。再実行しても既存 Draft を検出して停止します。既存 Draft とソースコミットを確認し、同一ソース・同一鍵で検証した APK を手動で添付して復旧してください。署名済み APK は通常の Actions Artifact には保存しません。必要なら手元で記録されたコミットから再ビルド・署名します。既存タグ・Release は自動削除しません。

補助スクリプトのテストは `python3 -B -m unittest discover -s scripts/tests -v` で実行できます。API と署名ツールをモックし、外部への書き込みや本物の鍵を使わずに停止条件・Draft 作成条件を検証します。実際の Secrets を使った署名と GitHub 上の一連の動作は、手動設定後に新しいタグを Push して確認してください。
