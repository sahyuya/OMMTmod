# OMMT — OyasaiMusic MIDI Translator

OMMT は、Minecraft クライアント内で MIDI または Note Block Studio（NBS）の曲を編集し、OyasaiMusic サーバープラグインへ非公開の下書き曲として送信する Fabric MOD です。Dear ImGui ベースのピアノロール、パート編集、音量・定位・テンポ・リリースのオートメーション、ローカル試聴を備えています。

ワールドへ音ブロックや実ブロックを書き出す MOD ではありません。曲の所有者、権限、ファイル検証、保存結果は常に OyasaiMusic サーバー側が決定します。

利用者向けの導入方法と、MIDI／NBS読込から編集・保存・下書き送信までの操作例は、[OMMTユーザーWiki](https://sahyuya.github.io/OMMTmod/)を参照してください。Wikiのソースは[`wiki/`](wiki/)にあり、GitHub Pagesへ自動公開されます。

## 主な機能

- `OMMT/midi` フォルダー内の `.mid` / `.midi` を一覧から読み込み
- `OMMT/nbs` フォルダー内の Note Block Studio `.nbs`（形式v0～v6）を一覧から読み込み
- 編集途中の曲を `OMMT/saves` の `.ommt` プロジェクトとして保存・再読込
- MIDI のトラック・チャンネル、NBSのレイヤーと音源を保ったパート分けと全体表示／パート別編集
- General MIDI番号に加え、トラック名・楽器名も利用した初期楽器の推定（読込後に手動変更可能）
- 選択音を新規または既存パートへ移動し、パート名を任意に変更
- ピアノロールの範囲選択、移動、長さ変更、コピー、貼り付け、切り取り、Undo／Redo
- 小節・拍・スナップに沿った編集と、ズーム量に応じたグリッドの間引き
- 独立してドック／タブ化できる、音量、定位、テンポ、リリースの下部編集パネル
- 選択音だけに設定する疑似リリースと、ミリ秒または1/1～1/64音価による長さ指定
- 配色テーマ、ドッキング配置、キーマップを含む編集設定の文字列インポート／エクスポート
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

Minecraft 1.21.11 の現在の開発・検証構成は次の通りです。

| 項目 | バージョン |
|---|---:|
| Minecraft | 1.21.11 |
| Java | 21 |
| Fabric Loader | 0.19.2 |
| Fabric API | 0.141.6+1.21.11 |
| Fabric Language Kotlin | 1.13.13+kotlin.2.4.10 |
| Fabric GUI ImGui | 1.21.11-1.0.11+imgui.1.92.0 |

サーバーへ下書きを送る場合は、接続先のバックエンドに対応版 OyasaiMusic と `oyasaimusic.import` 権限が必要です。MIDI／NBSの読込・編集・ローカル試聴だけであれば、OyasaiMusicがないサーバーやシングルプレイでも利用できます。

## インストールと起動

1. Minecraft版に合う Fabric Loader を導入します。
2. 上記4つの前提MODとOMMTのJARを、同じプロフィールの `mods` フォルダーへ入れます。
3. MODのクライアント初期化時に、ゲームディレクトリへ `OMMT/midi`、`OMMT/nbs`、`OMMT/saves` が作成されます。編集画面を一度開く必要はありません。
4. MIDIを `OMMT/midi`、NBSを `OMMT/nbs` に置き、ゲーム内で `O` キーを押してエディターを開きます。
5. 左ライブラリのMIDIまたはNBSタブから曲を選び、編集後に「下書き送信」を押します。

GUIスケール、配色、表示パネル、追従、グリッド密度、各操作のキーマップはエディターの設定画面に保存されます。ImGuiのドッキング配置はMinecraftの `config/ommt-imgui-layout.ini` に保存されます。「インポート / エクスポート」では、これらの設定と配置を1つの `OMMTCFG1:` 文字列として持ち運べます。この文字列に楽曲データは含まれません。

「編集を保存」は、送信前のノート、パート名、テンポ、音量・定位、選択音ごとの疑似リリース、表示位置などを `.ommt` へ保存します。これはローカル編集プロジェクトであり、OyasaiMusicへ送信する完成データではありません。MIDI、NBS、保存プロジェクトは左ライブラリの別タブから選択できます。

## Minecraftサウンドの選択

ノートインスペクターで楽器を「その他のMinecraftサウンド」にすると、検索欄、候補リスト、固定パターンの選択欄が表示されます。候補は `minecraft:` 名前空間のサウンドイベントです。パターン番号は正式版OyasaiMusicの `sound-catalog.json` にある `sounds` 配列の1始まりの番号で、サーバーが決定的な再生seedへ変換します。

26.1で追加されたブラス系ノートブロック音は、mainのOyasaiMusicが通知する実サーバーバージョン能力に従って表示します。1.21.11や能力通知のない旧OyasaiMusicでは安全側として候補へ出さず、26.1.2以降のmainから対応通知を受けた場合だけ候補へ加えます。対象は次の4イベントです。

- `minecraft:block.note_block.trumpet`
- `minecraft:block.note_block.trumpet_exposed`
- `minecraft:block.note_block.trumpet_weathered`
- `minecraft:block.note_block.trumpet_oxidized`

任意サウンドを含む曲は OYMI v3 として送信されます。OYMI v2は旧テスト版との読み込み互換専用で、新しい書き出しには使いません。固定パターンに対応しない旧OyasaiMusicへ内容を失った状態で自動変換せず、送信前に停止します。

## Minecraft版ごとの状態

Minecraft 26.1以降はゲーム本体が難読化されなくなり、従来のYarn名で書かれた1.21.11用MODは再コンパイルだけでなくソース移植が必要です。そのため、同じJARで複数版を兼用しません。

| クライアント版 | 状態 | 成果物 |
|---|---|---|
| 1.21.11 | 正式版 | `OyasaiMusicMidiTranslator-2.2.1-fabric1.21.11.jar` |
| 26.1.2 | 公式名API対応ビルド（実ゲーム受入は別途必要） | `OyasaiMusicMidiTranslator-2.2.1-fabric26.1.2.jar` |
| 26.2 | 公式名API対応ビルド（実ゲーム受入は別途必要） | `OyasaiMusicMidiTranslator-2.2.1-fabric26.2.jar` |

`versions/adapter-26/` は26.1.2／26.2用の最小アダプターです。編集モデルとImGui画面は1.21.11版と共有し、画面基底・入力・キー登録・通信ペイロードだけを26系の公式名APIへ接続します。1.21.11用JARの `fabric.mod.json` 対応範囲は広げず、Minecraft版ごとに別JARを使用してください。

## 開発者向けビルド

### バージョンの変更

3種類のJARを同じバージョンにするには、次の2ファイルの `mod_version` を同じ値へ変更します。

- `gradle.properties`（Minecraft 1.21.11）
- `versions/adapter-26/gradle.properties`（Minecraft 26.1.2／26.2）

成果物名はビルド設定が `OyasaiMusicMidiTranslator-<mod_version>-fabric<minecraft_version>.jar` の形で自動生成します。ソース内やJAR名を個別に書き換える必要はありません。今回のリリース値は両方とも `2.2.1` です。

## IntelliJ IDEA で開く場合

`OMMT` は Java 21 の Gradle/Fabric プロジェクトです。IntelliJ IDEA では、この `OMMT` ディレクトリを
**Gradle プロジェクトとして**開き、Gradle JVM を Java 21 に設定してから Gradle の再読み込みを完了させてください。
Minecraft・Fabric・Kotlin・ImGui の依存関係は Gradle が提供するため、個別の JAR を IDE のライブラリへ手動追加しません。

`versions/adapter-26` は Java 25 を使う独立した Gradle プロジェクトです。ルート `OMMT` の source set / content root に
含めないでください。26.1.2 または 26.2 のアダプターを直接編集するときだけ、そのディレクトリを**別の Gradle プロジェクト**として
リンクし、Gradle JVM を Java 25 にします。通常の OMMT 編集ではリンク不要です。

古い `.idea` がある状態で Minecraft/Fabric の import エラーが大量に出る場合は、IDE を閉じたうえで `OMMT/.idea` を削除し、
このディレクトリを再度 Gradle プロジェクトとして開いてください。legacy の `gradlew idea` は使用せず、IntelliJ の
通常の Gradle 再読み込みで source-set module を生成します。本リポジトリでは古い IDE メタデータを退避してから削除済みです。
依存関係のエラーを直すために Java 25 のアダプターを root へ `include` / `includeBuild` することはしないでください。

1.21.11版は Java 21 で次を実行します。

```powershell
.\gradlew.bat --no-daemon build12111
```

26.x版は Java 25 を使用します。同じ`OMMT`ディレクトリから個別または一括で実行できます。

```powershell
.\gradlew.bat --no-daemon build2612
.\gradlew.bat --no-daemon build262
.\gradlew.bat --no-daemon buildAllSupported
.\gradlew.bat --no-daemon verifyAllSupported
```

成果物は `build/libs/`、`versions/adapter-26/build/26.1.2/libs/`、`versions/adapter-26/build/26.2/libs/` に分離され、一方のビルドで他方を削除しません。ビルド・codec検証の成功は、実ゲームでの描画・入力・接続確認を代替しません。

共有通信仕様、サイズ上限、互換性、失敗時のフォールバックは [`../docs/interop/INTEROP_CONTRACT.md`](../docs/interop/INTEROP_CONTRACT.md) を参照してください。
