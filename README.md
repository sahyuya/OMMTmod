# OMMT — OyasaiMusic MIDI Translator

OMMT は、Minecraft クライアント内で MIDI または Note Block Studio（NBS）の曲を編集し、OyasaiMusic サーバープラグインへ非公開の下書き曲として送信する Fabric MOD です。Dear ImGui ベースのピアノロール、パート編集、音量・定位・テンポ・リリースのオートメーション、ローカル試聴を備えています。

ワールドへ音ブロックや実ブロックを書き出す MOD ではありません。曲の所有者、権限、ファイル検証、保存結果は常に OyasaiMusic サーバー側が決定します。

利用者向けの導入方法と、MIDI／NBS読込から編集・保存・下書き送信までの操作例は、[OMMTユーザーWiki](https://sahyuya.github.io/OMMTmod/)を参照してください。Wikiのソースは[`wiki/`](wiki/)にあり、GitHub Pagesへ自動公開されます。

## 主な機能

- `OMMT/midi` フォルダー内の `.mid` / `.midi` を一覧から読み込み
- `OMMT/nbs` フォルダー内の Note Block Studio `.nbs`（形式v0～v6）を一覧から読み込み
- MIDIのサステインペダルをノート長へ反映し、密なテンポ列を軽量な線／カーブへ整理
- NBSのレイヤー音量・定位、単音定位を保持し、微細ピッチを最も近いOMMT音高へ反映。対応するカスタムMinecraftサウンドも固定パターンで保持
- 数万音・数百レイヤーの曲でも、表示中のノートとページ内パートだけを描画する大規模曲向け表示
- 編集途中の曲を `OMMT/saves` の `.ommt` プロジェクトとして保存・再読込
- MIDI のトラック・チャンネル、NBSのレイヤーと音源を保ったパート分けと全体表示／パート別編集
- General MIDI番号に加え、トラック名・楽器名も利用した初期楽器の推定（読込後に手動変更可能）
- 選択音を新規または既存パートへ移動し、パート名を任意に変更
- ピアノロールの範囲選択、移動、長さ変更、コピー、貼り付け、切り取り、Undo／Redo
- 小節・拍・スナップに沿った編集と、ズーム量に応じたグリッドの間引き
- 独立してドック／タブ化できる、音量、定位、テンポ、リリースの下部編集パネル
- 選択音だけに設定する疑似リリースと、ミリ秒または1/1～1/64音価による長さ指定
- 配色テーマ、ドッキング配置、キーマップを含む編集設定の文字列インポート／エクスポート
- 純正DAWを参考にした「DAWグラファイト」配色、ルーラー、選択輪郭、テーマ連動オートメーション表示
- バニラ音域外のノートも画面上では保持し、試聴・送信時だけ再生可能音域へ収める処理
- ノートブロック16音色に加え、「その他のMinecraftサウンド」からサウンドIDと固定パターンを検索して選択
- OyasaiMusic への圧縮・分割Plugin Message送信と進捗表示（サーバーREADY後のみ本体送信）
- 対応サーバーでの先読みバッファ再生。MOD未導入クライアントは通常のサーバー再生へ安全にフォールバック
- PROBE応答、バッファ受信、READY、ローカル再生開始・停止・完了をクライアントの `logs/latest.log` へ記録

## 必要な前提 MOD

OMMT本体と同じMinecraft版向けの、次のMODをクライアントへ導入してください。

- Fabric Loader
- Fabric API
- Fabric Language Kotlin
- Fabric GUI ImGui

OMMT 3.0.5 は Minecraft 26.2 と 26.3 向けに別々の JAR を生成します。使用する Minecraft と同じバージョンの JAR と依存 MOD を選び、OMMT の JAR は一つだけ入れてください。ビルドと共通検証は両方で実行していますが、26.3 の実ゲームでの起動・描画・入力・音声・サーバー連携は未確認です。

| 項目 | Minecraft 26.2 | Minecraft 26.3 |
|---|---|---|
| Java | 25 | 25 |
| Fabric Loader | 0.19.3 | 0.20.1 |
| Fabric API | 0.158.0+26.2 | 0.165.0+26.3 |
| Fabric Language Kotlin | 1.13.13+kotlin.2.4.10 | 1.14.2+kotlin.2.4.21 |
| Fabric GUI ImGui | 26.2-1.1.0+imgui.1.92.0 | 26.3-1.2.1+imgui.1.92.7.1 |

サーバーへ下書きを送る場合は、接続先のバックエンドに対応版 OyasaiMusic と `oyasaimusic.import` 権限が必要です。MIDI／NBSの読込・編集・ローカル試聴だけであれば、OyasaiMusicがないサーバーやシングルプレイでも利用できます。

## インストールと起動

1. Minecraft版に合う Fabric Loader を導入します。
2. 上記4つの前提MODとOMMTのJARを、同じプロフィールの `mods` フォルダーへ入れます。
3. MODのクライアント初期化時に、ゲームディレクトリへ `OMMT/midi`、`OMMT/nbs`、`OMMT/saves` が作成されます。編集画面を一度開く必要はありません。
4. MIDIを `OMMT/midi`、NBSを `OMMT/nbs` に置き、ゲーム内で `O` キーを押してエディターを開きます。
5. 左ライブラリのMIDIまたはNBSタブから曲を選び、編集後に「下書き送信」を押します。

GUIスケール、配色、表示パネル、追従、グリッド密度、各操作のキーマップはエディターの設定画面に保存されます。ImGuiのドッキング配置はMinecraftの `config/ommt-imgui-layout.ini` に保存されます。「インポート / エクスポート」では、これらの設定と配置を1つの `OMMTCFG1:` 文字列として持ち運べます。この文字列に楽曲データは含まれません。

「編集を保存」は、送信前のノート、パート名、テンポ、音量・定位、選択音ごとの疑似リリース、表示位置などを `.ommt` へ保存します。これはローカル編集プロジェクトであり、OyasaiMusicへ送信する完成データではありません。元ファイルと保存プロジェクトは左ライブラリの「MIDI」「NBS」「保存」タブから選択できます。

## Minecraftサウンドの選択

ノートインスペクターで楽器を「その他のMinecraftサウンド」にすると、検索欄、候補リスト、固定パターンの選択欄が表示されます。候補は `minecraft:` 名前空間のサウンドイベントです。パターン番号は正式版OyasaiMusicの `sound-catalog.json` にある `sounds` 配列の1始まりの番号で、サーバーが決定的な再生seedへ変換します。

Minecraft 26.2 で使用可能なラッパ系ノートブロック音は、通常の楽器候補として利用できます。対象は次の4イベントです。

- `minecraft:block.note_block.trumpet`
- `minecraft:block.note_block.trumpet_exposed`
- `minecraft:block.note_block.trumpet_weathered`
- `minecraft:block.note_block.trumpet_oxidized`

任意サウンドを含む曲は OYMI v3 として送信されます。OYMI v2は旧テスト版との読み込み互換専用で、新しい書き出しには使いません。固定パターンに対応しない旧OyasaiMusicへ内容を失った状態で自動変換せず、送信前に停止します。

## Minecraftバージョン

OMMT 3.0.5 の対象は Minecraft 26.2 と 26.3 です。1.21.11 と26.1.2 向けの新しい成果物は生成しません。

| クライアント版 | 状態 | 成果物 |
|---|---|---|
| 26.2 | 維持対象 | `OyasaiMusicMidiTranslator-3.0.5-fabric26.2.jar` |
| 26.3 | ビルド・共通検証済み、実機未確認 | `OyasaiMusicMidiTranslator-3.0.5-fabric26.3.jar` |

`versions/adapter-26/` は 26.2 用、`versions/mc263/` は 26.3 用のアダプターです。26.3 では SDL3 の入力を既存の編集キーマップへ変換し、保存済みのキー値を変更しません。

## 開発者向けビルド

### バージョンの変更

JARのバージョンを変更するには、ルートの `gradle.properties` にある `mod_version` を変更します。

成果物名は `OyasaiMusicMidiTranslator-<mod_version>-fabric<Minecraftのバージョン>.jar` の形で自動生成されます。両方のビルドはルートの `mod_version=3.0.5` を参照します。ビルドの入口はルートの Gradle プロジェクトです。

## IntelliJ IDEA で開く場合

`OMMT` は Java 25 の Gradle/Fabric プロジェクトです。IntelliJ IDEA では、この `OMMT` ディレクトリを
**Gradle プロジェクトとして**開き、Gradle JVM を Java 25 に設定してから Gradle の再読み込みを完了させてください。
Minecraft・Fabric・Kotlin・ImGui の依存関係は Gradle が提供するため、個別の JAR を IDE のライブラリへ手動追加しません。

`versions/adapter-26` のソースと `mc263` サブプロジェクトはルートから自動で読み込まれるため、IDE で別プロジェクトとしてリンクする必要はありません。

古い `.idea` がある状態で Minecraft/Fabric の import エラーが大量に出る場合は、IDE を閉じたうえで `OMMT/.idea` を削除し、
このディレクトリを再度 Gradle プロジェクトとして開いてください。legacy の `gradlew idea` は使用せず、IntelliJ の
通常の Gradle 再読み込みで source-set module を生成します。本リポジトリでは古い IDE メタデータを退避してから削除済みです。
Java 25 で次を実行します。`build262` と `build263` はそれぞれの JAR と共通検証を実行し、`buildAllSupported` と `verifyAllSupported` は両方を対象にします。JUnit の test タスクは無効で、共通検証は `verifyUploadCodec` の実行結果を確認します。

```powershell
.\gradlew.bat --no-daemon build262
.\gradlew.bat --no-daemon build263
.\gradlew.bat --no-daemon buildAllSupported
.\gradlew.bat --no-daemon verifyAllSupported
```

26.2 の成果物は `build/libs/OyasaiMusicMidiTranslator-3.0.5-fabric26.2.jar`、26.3 は `versions/mc263/build/26.3/libs/OyasaiMusicMidiTranslator-3.0.5-fabric26.3.jar` です。ビルド・codec検証の成功は、実ゲームでの描画・入力・接続確認を代替しません。

共有通信仕様、サイズ上限、互換性、失敗時のフォールバックは [`../docs/interop/INTEROP_CONTRACT.md`](../docs/interop/INTEROP_CONTRACT.md) を参照してください。

## MOD内フォントについて / About the fonts in this MOD
[License File](https://github.com/sahyuya/OMMTmod/blob/35f19bb9f9b029b4d749b6a60da1a7d4774e3e66/src/client/resources/assets/oyasaimusicmiditranslator/fonts/OFL.txt)

本Modには、GoogleおよびAdobeが開発したフォント「Noto Sans JP」が含まれています。
このフォントは SIL Open Font License 1.1 に基づいて配布されています。

Credits:This mod includes "Noto Sans JP" designed by Google and Adobe, which is licensed under the SIL Open Font License 1.1.
- Noto Sans JP: google.com
- SIL Open Font License 1.1: openfontlicense.org
