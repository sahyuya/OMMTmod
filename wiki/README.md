# OMMT user wiki source

このディレクトリは、OMMT利用者向けGitHub Pagesの公開元です。ビルドツールや外部ライブラリを使わない静的HTML/CSSと少量のバニラJSで構成し、`master`へ変更が入ると `.github/workflows/pages.yml` がこのディレクトリだけを公開します。

公開URL: https://sahyuya.github.io/OMMTmod/

## ページ構成

日本語ページと英語ページ（`-en`）の対訳構成です。各ページのヘッダーから言語を切り替えられます。

- `index.html` / `index-en.html`: Wikiトップと利用の流れ
- `install.html` / `install-en.html`: 導入、対応バージョン、MIDI／NBSフォルダー
- `editing.html` / `editing-en.html`: 読み込み、ノート・パート編集、スナップ、ショートカット
- `sound.html` / `sound-en.html`: 楽器・サウンド、音量・定位・テンポ・疑似リリース
- `save-upload.html` / `save-upload-en.html`: `.ommt`保存、試聴、下書き送信
- `settings.html` / `settings-en.html`: 設定リファレンス（一般・配色・レイアウト・キーマップ・共有）
- `help.html` / `help-en.html`: トラブルシューティングと報告先
- `404.html`: 日英併記のNot Found
- `assets/styles.css`: 全ページ共通の表示（ライト／ダーク自動切替＋手動切替対応）
- `assets/theme.js`: テーマ切替ボタンと目次の現在位置ハイライト

## ローカル確認

`wiki`をルートにした静的Webサーバーで表示します。Pythonが利用できる場合は、`OMMT`ディレクトリから次を実行し、ブラウザーで `http://localhost:8000/` を開きます。

```powershell
python -m http.server 8000 --directory wiki
```

HTMLファイルを直接開いても閲覧できますが、公開時と同じ相対リンク確認にはローカルWebサーバーを推奨します。

## GitHub Pagesを初めて有効にする場合

GitHubのリポジトリで **Settings → Pages → Build and deployment → Source** を **GitHub Actions** に設定します。その後、`master`へWikiを含む変更をpushすると公開ワークフローが実行されます。以後は `wiki/**` の変更ごとに更新されます。
