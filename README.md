# SquidRun

SquidRunは、THINKLETを用いたRTMP(S)によるライブストリーミング配信を行うためのアプリです。

## 機能

- RTMP(S)によるライブストリーミング配信
    - Youtube Liveを含む任意のRTMP(S)サーバに配信可能

## 注意点

- 縦広角モデルでは縦長の解像度、横広角モデルでは横長の解像度のみをサポートしています。
    - 縦広角モデルの場合、起動オプションの`longSide`が縦の解像度、`shortSide`が横の解像度となります。

## 操作

アプリ起動後、以下の操作が可能です。

| ボタン   | 短押し               | 長押し |
|-------|-------------------|-----|
| 第1ボタン | 無し                | 無し  |
| 第2ボタン | ストリーミングの開始/終了     | 無し  |
| 第3ボタン | オーディオのミュートの切り替え   | 無し  |
| 電源ボタン | ストリーミングの終了・アプリの終了 | 無し  |

## フィードバック

### エラー時のフィードバック

起動時またはストリーミングの開始時などにエラーを検知した場合、以下のバイブレーションパターンでエラーを通知します。

| タイミング       | パターン | 意味                    |
|-------------|------|-----------------------|
| 起動時         | 2回   | 必要な権限が全て付与されていない場合    |
| 起動時         | 3回   | 必須の起動オプションが設定されていない場合 |
| ストリーミングの開始時 | 2回   | ストリーミングの開始に失敗した場合     |

### 操作時のフィードバック

操作時には、以下のバイブレーションパターンで操作を通知します。

| タイミング          | パターン |
|----------------|------|
| オーディオをミュートした   | 2回   |
| オーディオをミュート解除した | 1回   |

## 起動オプション

以下のオプションを指定可能です。
オプションはアプリ起動のためのIntent Extraとして指定します。

| キー                | 値の型     | 必須 | 規定値     | 説明                                                                                             |
|-------------------|---------|----|---------|------------------------------------------------------------------------------------------------|
| `streamUrl`       | String  | ○  |         | RTMP(S)サーバのURL                                                                                 |
| `streamKey`       | String  | ○  |         | ストリームキー                                                                                        |
| `longSide`        | Int     |    | 720     | ストリームの長辺の解像度                                                                                   |
| `shortSide`       | Int     |    | 480     | ストリームの短辺の解像度                                                                                   |
| `orientation`     | String  |    |         | 指定された方向の解像度を強制する。<br/>`landscape`の場合は横長、`portrait`の場合は縦長となる。<br/>未指定の場合はデバイスのモデルに応じた方向の解像度になる。 |
| `videoBitrate`    | Int     |    | 4096    | ビデオのビットレート。単位はkbps。                                                                            |
| `audioSampleRate` | Int     |    | 48000   | オーディオのサンプリングレート。単位はHz。                                                                         |
| `audioBitrate`    | Int     |    | 128     | オーディオのビットレート。単位はkbps。                                                                          |
| `audioChannel`    | String  |    | stereo  | オーディオのチャンネル数。<br/>`monaural`の場合は1チャンネル、`stereo`の場合は2チャンネルとなる。                                  |
| `echoCanceler`    | Boolean |    | false   | エコーキャンセラーを有効にするかどうか。                                                                           |
| `micMode`         | String  |    | android | マイクモードの設定。下記のマイクモードを参照                                                                         |
| `preview`         | Boolean |    | false   | 画面上にストリーミングのプレビューを表示するかどうか。<br/>デバッグ時以外はバッテリー節約のためOFFを推奨。                                      |

#### マイクモード

| 値         | 説明                                                                                                                             |
|-----------|--------------------------------------------------------------------------------------------------------------------------------|
| android   | Androidの標準実装を使用する。`audioChannel`に応じて1つまたは2つのマイクを使用する。                                                                          |
| thinklet5 | THINKLETの5つのマイクを使用する。`audioChannel`に応じて複数のマイクの音声を合成する。<br/>`audioSampleRate`は`16000`, `32000`, `48000`のどれかである必要がある。            |
| thinklet6 | THINKLETの5つのマイクに加えて参照音用マイクを使用する。`audioChannel`に応じて複数のマイクの音声を合成する。<br/>`audioSampleRate`は`16000`, `32000`, `48000`のどれかである必要がある。 |

### adbコマンドによる起動

#### コマンド例

```shell
adb shell am start \
    -n ai.fd.thinklet.app.squid.run/.MainActivity \
    -a android.intent.action.MAIN \
    -e streamUrl "rtmp://example.com/live" \
    -e streamKey "stream_key" \
    --ei longSide 720 \
    --ei shortSide 480 \
    --ei videoBitrate 4096 \
    --ei audioSampleRate 44100 \
    --ei audioBitrate 128 \
    --ez preview true
```

## ビルド

### セットアップ

#### GitHub Packagesのセットアップ
[THINKLET App SDK](https://github.com/FairyDevicesRD/thinklet.app.sdk)を使用しているため、配布先であるGitHub Packagesへアクセス可能にするための準備が必要です。

[スタートガイド](https://fairydevicesrd.github.io/thinklet.app.developer/docs/startGuide/buildMultiMic) の `THINKLET App SDK の導入` の項目を行い、新規作成した `github.properties` ファイルに `username` および `token` を設定してください。

#### 署名の設定

`app/build.gradle.kts` ファイルを編集し、`signingConfigs` ブロックに署名情報を設定してください。

### ビルド

以下のコマンドでビルドを行います。

```shell
./gradlew :app:assembleRelease
```

## 付録

### テスト用RTMPサーバの用意

このアプリを使用したストリーミング機能を動作させるためには、RTMPサーバーが必要です。
YouTube Liveなどの外部サービスを使用することもできますが、ローカル環境でのテストを行いたい場合は、以下の手順でRTMPサーバーを用意することができます。

#### MediaMTX の準備

ここでは、[MediaMTX](https://github.com/bluenviron/mediamtx) を使用してRTMPサーバーを構築します。
リリースページより、サーバーを動かす環境に合った最新のリリースをダウンロードしてください。
このドキュメントでは、Apple Silicon版のmacOSをRTMPサーバとして使用する場合を紹介します。
※MediaMTXを実行しているマシンとTHINKLETは同じネットワークに接続する必要があります。

1. ダウンロードした.tar.gzファイルを解凍します。
2. 解凍したフォルダをターミナルで開き、`mediamtx` を実行します。

このRTMPサーバにストリーミングを行う場合、起動時パラメーターは以下のように設定します。

```
streamUrl: rtmp://[MediaMTXを実行しているマシンのIPアドレス]:1935
streamKey: [任意の文字列]
```

### ストリームされた映像を確認する

ストリーミングされた映像を確認するためには、ffmpegに付属する[ffplay](https://ffmpeg.org/ffplay.html)を使用すると便利です。

#### ffplay の準備

[ffmpegのダウンロードページ](https://ffmpeg.org/download.html)から、自分の環境に合ったバイナリをダウンロードしてください。
※MediaMTXを実行しているマシンと同じマシンもしくは同じネットワークに接続する必要があります。

1. ffmpegをダウンロード・およびインストール(pathに通し)します。
2. ターミナルを開き、以下のコマンドを実行します。

```
ffplay rtmp://[MediaMTXを実行しているマシンのIPアドレス]:1935/[streamKey]
```

`[streamKey]` は、MediaMTXの起動時に指定した `streamKey` と同じものを指定してください。
