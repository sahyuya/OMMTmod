# OMMT — OyasaiMusic MIDI Translator

OMMT は、Minecraft クライアント内で MIDI を編集し、OyasaiMusic サーバープラグインへ非公開の下書き曲として送信する Fabric MOD です。Dear ImGui ベースのピアノロール、パート編集、音量・定位・テンポ・リリースのオートメーション、ローカル試聴を備えています。

ワールドへ音ブロックや実ブロックを書き出す MOD ではありません。曲の所有者、権限、ファイル検証、保存結果は常に OyasaiMusic サーバー側が決定します。

## 主な機能

- `OMMT/midi` フォルダー内の `.mid` / `.midi` を一覧から読み込み
- MIDI のトラック・チャンネルを保ったパート分けと全体表示／パート別編集
- ピアノロールの範囲選択、移動、長さ変更、コピー、貼り付け、切り取り、Undo／Redo
- 小節・拍・スナップに沿った編集と、ズーム量に応じたグリッドの間引き
- 独立してドック／タブ化できる、音量、定位、テンポ、リリースの下部編集パネル
- バニラ音域外のノートも画面上では保持し、試聴・送信時だけ再生可能音域へ収める処理
- ノートブロック16音色に加え、「その他のMinecraftサウンド」からサウンドIDと固定パターンを検索して選択
- OyasaiMusic への圧縮・分割コマンド送信と進捗表示
- 対応サーバーでの先読みバッファ再生。MOD未導入クライアントは通常のサーバー再生へ安全にフォールバック

## 必要な前提 MOD

OMMT本体と同じMinecraft版向けの、次のMODをクライアントへ導入してください。

- Fabric Loader
- Fabric API
- Fabric Language Kotlin
- Fabric GUI ImGui

Minecraft 1.21.11 の現在の開発・検証構成は次の通りです。

| 項目 | バージョン |
|---|---:|
| Minecraft | 1.21.11 |
| Java | 21 |
| Fabric Loader | 0.19.2 |
| Fabric API | 0.141.6+1.21.11 |
| Fabric Language Kotlin | 1.13.13+kotlin.2.4.10 |
| Fabric GUI ImGui | 1.21.11-1.0.11+imgui.1.92.0 |

サーバーへ下書きを送る場合は、接続先のバックエンドに対応版 OyasaiMusic と `oyasaimusic.import` 権限が必要です。MIDIの読込・編集・ローカル試聴だけであれば、OyasaiMusicがないサーバーやシングルプレイでも利用できます。

## インストールと起動

1. Minecraft版に合う Fabric Loader を導入します。
2. 上記4つの前提MODとOMMTのJARを、同じプロフィールの `mods` フォルダーへ入れます。
3. 一度起動すると、ゲームディレクトリに `OMMT/midi` が作成されます。
4. MIDIファイルを `OMMT/midi` に置き、ゲーム内で `O` キーを押してエディターを開きます。
5. 左のMIDIライブラリから曲を選び、編集後に「下書き送信」を押します。

GUIスケール、表示パネル、追従、グリッド密度、送信方式、各操作のキーマップはエディターの設定画面に保存されます。ImGuiのドッキング配置はMinecraftの `config/ommt-imgui-layout.ini` に保存されます。

## Minecraftサウンドの選択

ノートインスペクターで楽器を「その他のMinecraftサウンド」にすると、検索欄、候補リスト、固定パターンの選択欄が表示されます。候補は `minecraft:` 名前空間のサウンドイベントです。パターン番号は正式版OyasaiMusicの `sound-catalog.json` にある `sounds` 配列の1始まりの番号で、サーバーが決定的な再生seedへ変換します。

サーバーはMinecraft 1.21.11で動作するため、26.1で追加された銅系ノートブロックのトランペット音は、26.xクライアントでも候補へ出しません。除外対象は次の4イベントです。

- `minecraft:block.note_block.trumpet`
- `minecraft:block.note_block.trumpet_exposed`
- `minecraft:block.note_block.trumpet_weathered`
- `minecraft:block.note_block.trumpet_oxidized`

任意サウンドを含む曲は OYMI v3 として送信されます。OYMI v2は旧テスト版との読み込み互換専用で、新しい書き出しには使いません。固定パターンに対応しない旧OyasaiMusicへ内容を失った状態で自動変換せず、送信前に停止します。

## Minecraft版ごとの状態

Minecraft 26.1以降はゲーム本体が難読化されなくなり、従来のYarn名で書かれた1.21.11用MODは再コンパイルだけでなくソース移植が必要です。そのため、同じJARで複数版を兼用しません。

| クライアント版 | 状態 | 成果物 |
|---|---|---|
| 1.21.11 | 正式版 | `OyasaiMusicMidiTranslator-1.0.0.jar` |
| 26.1.2 | 公式名API対応ビルド（実ゲーム受入は別途必要） | `OyasaiMusicMidiTranslator-mc26.1.2-1.0.0.jar` |
| 26.2 | 公式名API対応ビルド（実ゲーム受入は別途必要） | `OyasaiMusicMidiTranslator-mc26.2-1.0.0.jar` |

`OMMT-26/` は26.1.2／26.2用の独立ビルド入口です。編集モデルとImGui画面は1.21.11版と共有し、画面基底・入力・キー登録・通信ペイロードだけを26系の公式名APIへ接続します。1.21.11用JARの `fabric.mod.json` 対応範囲は広げず、Minecraft版ごとに別JARを使用してください。

## 開発者向けビルド

1.21.11版は Java 21 で次を実行します。

```powershell
.\gradlew.bat --offline --no-daemon clean build verifyUploadCodec
```

26.x版は Java 25 と Gradle 9.6.1を使用します。既定の26.2版は `OMMT-26/` で同じタスクを実行します。26.1.2版は次のGradleプロジェクトプロパティを環境変数で指定して同じタスクを実行します。

```powershell
$env:ORG_GRADLE_PROJECT_minecraft_version='26.1.2'
$env:ORG_GRADLE_PROJECT_fabric_version='0.155.2+26.1.2'
$env:ORG_GRADLE_PROJECT_fabric_gui_imgui_version='26.1-1.0.11+imgui.1.92.0'
gradle --offline --no-daemon clean build verifyUploadCodec
```

成果物は `OMMT-26/build/26.1.2/libs/` と `OMMT-26/build/26.2/libs/` に分離され、一方のビルドで他方を削除しません。ビルド・codec検証の成功は、実ゲームでの描画・入力・接続確認を代替しません。

共有通信仕様、サイズ上限、互換性、失敗時のフォールバックは [`../docs/interop/INTEROP_CONTRACT.md`](../docs/interop/INTEROP_CONTRACT.md) を参照してください。
