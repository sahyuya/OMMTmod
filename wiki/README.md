# OMMT user wiki source

このディレクトリは、OMMT利用者向けGitHub Pagesの公開元です。ビルドツールや外部ライブラリを使わない静的HTML/CSSで構成し、`master`へ変更が入ると `.github/workflows/pages.yml` がこのディレクトリだけを公開します。

公開URL: https://sahyuya.github.io/OMMTmod/

## ページ構成

- `index.html`: Wikiトップと利用の流れ
- `install.html`: 導入、対応Minecraft版、MIDI／NBSフォルダー
- `editing.html`: MIDI／NBS読込、ノート・パート・各編集パネル
- `save-upload.html`: `.ommt`保存、試聴、下書き送信
- `settings-help.html`: 表示・操作設定、設定共有、トラブルシューティング
- `assets/styles.css`: 全ページ共通の表示

## ローカル確認

`wiki`をルートにした静的Webサーバーで表示します。Pythonが利用できる場合は、`OMMT`ディレクトリから次を実行し、ブラウザーで `http://localhost:8000/` を開きます。

```powershell
python -m http.server 8000 --directory wiki
```

HTMLファイルを直接開いても閲覧できますが、公開時と同じ相対リンク確認にはローカルWebサーバーを推奨します。

## GitHub Pagesを初めて有効にする場合

GitHubのリポジトリで **Settings → Pages → Build and deployment → Source** を **GitHub Actions** に設定します。その後、`master`へWikiを含む変更をpushすると公開ワークフローが実行されます。以後は `wiki/**` の変更ごとに更新されます。
