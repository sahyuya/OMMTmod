# OMMT user wiki

おやさいサーバー向けOMMTの利用者向けガイド。日本語14記事・英語14記事で、導入、最初の1曲、目的別の編集、設定・用語・問題解決を扱います。

## 記事を編集する

本文の編集元は `tools/wiki/content.mjs` です。生成されたHTMLを直接編集せず、OMMTディレクトリで次を実行してください。

```powershell
node tools/wiki/build.mjs
node tools/wiki/verify.mjs
```

生成先は `wiki/`。見出し・本文から検索インデックスも生成します。Node.js以外のビルド依存は不要です。通常のverifyはHTML、内部リンク、画像、見出しIDを確認します。

ブラウザー検証はPlaywrightのあるnode_modulesを環境変数 `WIKI_NODE_MODULES` に指定して同じverifyを実行します。Microsoft Edgeが必要です。これは公開ワークフローの必須依存ではありません。

## 構成

- `tools/wiki/content.mjs`: 対訳記事とページ順序
- `tools/wiki/build.mjs`: HTMLと全文検索インデックスの生成
- `tools/wiki/verify.mjs`: 静的検証と任意のブラウザー検証
- `wiki/assets/styles.css`: 共通デザイン、モバイル、印刷
- `wiki/assets/wiki.js`: 検索、目次、モバイルメニュー
- `wiki/assets/theme.js`: 自動・ライト・ダーク配色
- `wiki/IMAGE_REQUESTS.md`: 実画面の撮影依頼と記事内配置先

検索はブラウザー内で完結し、検索語を外部サービスへ送信しません。JavaScriptが無効でも記事とページ一覧は読めます。既存の記事URLと主な旧見出しIDを残しています。旧見出しの一部は、その記事の先頭へ移動します。

## 確認と公開

`wiki/index.html` は直接ブラウザーで開けます。公開前には静的HTTPサーバーでも確認してください。

GitHub PagesのSourceは **GitHub Actions** を使用します。`master` へwiki、tools/wiki、gradle.propertiesの変更をpushすると、記事生成・静的検証後に公開します。編集しただけでは公開されません。

公開先: https://sahyuya.github.io/OMMTmod/

## 編集方針

1ページに1つの目的を置き、操作手順と結果を先に説明します。機能名の羅列や更新履歴の追記で本文を増やさず、対応する記事を更新します。画面画像は実際の現行MODから用意し、内部実装の説明や架空のUI画像は利用者向け手順へ混ぜません。
