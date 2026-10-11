# Android CI

`.github/workflows/android-ci.yml` は全ブランチ・タグへの push と、Pull Request の作成・更新・再オープン時に実行されます。同じイベントと ref の古い実行はキャンセルします。ブランチへの push と PR 更新は別々の実行になります。

## 環境とチェック

- `ubuntu-latest`、Temurin JDK 25（`gradle/gradle-daemon-jvm.properties` の指定）。Java のコンパイル設定 11 とは別です。
- Gradle Wrapper 9.6.0、AGP 9.4.1。Wrapper の JAR と配布 ZIP の SHA-256 検証を利用します。
- compileSdk / targetSdk 37、minSdk 26。SDK パッケージは `platforms;android-37.0`、AGP の既定の Build-Tools は `36.0.0` です。
- Gradle キャッシュは `gradle/actions/setup-gradle` に集約し、PR とデフォルトブランチ以外では読み取り専用にします。追加 Secrets は不要です。Gradle の configuration cache は暗号化キーを追加しないため Actions 間で保存しません。

実行コマンド:

```sh
bash gradlew assembleDebug testDebugUnitTest lintDebug --continue --no-daemon --max-workers=2 --console=plain
```

`gradlew` は Git 上で実行権限がないため `bash` 経由で使います。`--continue` により可能な独立タスクを継続しますが、テストまたは Lint エラーがあれば終了コードは非ゼロで CI は失敗します。Lint の警告だけでは失敗しません。

既存 JVM テストは 11 件（保存処理 7、縮小率 3、雛形 1）です。`androidTest` の 8 件は端末が必要な計装テストで、この CI では実行しません。

## GitHub 上での確認

1. 作業ブランチを push し、`main` 向けの Pull Request を作成します。たとえばこの変更では `git push -u origin ci/android-github-actions` を実行します。
2. リポジトリの **Actions → Android CI** または PR の **Checks** から実行結果を確認します。初回や外部 contributor の PR では、管理者によるワークフロー実行承認が必要な場合があります。
3. **Build, unit tests, and lint** が成功し、指定の 3 タスクが完了していることを確認します。ローカルでの検証だけでは、GitHub runner 上の SDK 導入、キャッシュ復元・保存、Artifacts のアップロード成功までは確認できません。
4. 必須チェックにする場合は管理者がブランチ保護／Rulesets にこのジョブを登録してください。この変更では保護設定を変更しません。

## 失敗した場合

実行画面で失敗したステップを開き、Gradle の `FAILED` タスクと末尾の例外を確認します。テスト／Lint レポートが生成されていれば、成否にかかわらず **MyImageViewer-ci-reports**（7 日間保存）から確認できます。セットアップ失敗などでレポートがない場合は実行ログを確認してください。

レポートには JUnit XML、テスト HTML、Lint HTML／XML／SARIF が含まれます。原因の修正を push すると CI が再実行されます。一時的な通信エラーでは GitHub の **Re-run failed jobs** も利用できます。

## APK のダウンロード

成功した実行の **Summary → Artifacts → MyImageViewer-debug-apk** から ZIP をダウンロードし、展開して `app-debug.apk` を取得します（GitHub へのログインが必要）。保存期間は 7 日間です。ビルド・テスト・Lint のいずれかに失敗した実行では APK を保存しません。

APK は Android 標準のデバッグ鍵で署名される開発用です。リリース鍵・パスワード・追加 Secrets は使いません。公開版と署名が異なるため、公開版への上書きインストールはできません。アンインストールすると保存画像も削除されるため、実機確認時は注意してください。

## 権限と変更範囲

GitHub トークン権限は `contents: read` のみで、checkout の認証情報も保持しません。外部 Action は公式または広く使われる Action のコミット SHA に固定しています。リリースビルド・タグ作成・GitHub Releases への公開処理はありません。アプリ、署名設定、依存関係、既存 Gradle 設定は変更しません。
