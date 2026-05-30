import { useEffect, useState, useCallback } from 'react';
import { IonApp, IonContent, IonButton, IonText } from '@ionic/react';
import { Overlay } from './overlay';

export default function App() {
  const [granted, setGranted] = useState(false);
  const [running, setRunning] = useState(false);
  const [updating, setUpdating] = useState(false);
  const [updateMsg, setUpdateMsg] = useState('');
  const [cookies, setCookies] = useState(false);

  // 権限・稼働状態をネイティブから取得して同期（ゴミ箱終了にも追従）
  const sync = useCallback(async () => {
    try {
      const [perm, run, ck] = await Promise.all([
        Overlay.checkPermission(),
        Overlay.isRunning(),
        Overlay.hasCookies(),
      ]);
      setGranted(perm.granted);
      setRunning(run.running);
      setCookies(ck.present);
    } catch { /* web preview: plugin unavailable */ }
  }, []);

  useEffect(() => {
    sync();
    // アプリが前面に戻るたびに再同期（バックグラウンドでゴミ箱終了した場合に対応）
    const onVisible = () => {
      if (document.visibilityState === 'visible') sync();
    };
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('focus', onVisible);
    return () => {
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('focus', onVisible);
    };
  }, [sync]);

  const startOverlay = async () => {
    await Overlay.requestPermission();   // 未許可なら設定画面へ
    try {
      await Overlay.start();
    } catch (e) {
      console.warn('start failed (権限未許可の可能性):', e);
    }
    await sync();
  };

  const stopOverlay = async () => {
    await Overlay.stop();
    await sync();
  };

  const updateYtdlp = async () => {
    setUpdating(true);
    setUpdateMsg('更新中…（初回は数十秒かかることがあります）');
    try {
      const { result } = await Overlay.update();
      setUpdateMsg('yt-dlp 更新: ' + result);
    } catch (e: any) {
      setUpdateMsg('更新失敗: ' + (e?.message ?? e));
    } finally {
      setUpdating(false);
    }
  };

  const importCookies = async () => {
    try {
      await Overlay.importCookies();
    } catch { /* キャンセル等は無視 */ }
    await sync();
  };

  const clearCookies = async () => {
    await Overlay.clearCookies();
    await sync();
  };

  return (
    <IonApp>
      <IonContent>
        <div
          style={{
            minHeight: '100%',
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'center',   // 縦中央寄せ
            padding: 24,
            boxSizing: 'border-box',
          }}
        >
          <h1 style={{ fontSize: 26, margin: '0 0 24px' }}>AllmightDownloader</h1>

          <div style={{
            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            padding: '12px 0', borderBottom: '1px solid #eee', marginBottom: 24,
          }}>
            <span>オーバーレイ権限</span>
            <IonText color={granted ? 'success' : 'danger'}>
              {granted ? '許可済み' : '未許可'}
            </IonText>
          </div>

          {!running ? (
            <IonButton expand="block" onClick={startOverlay}>浮遊ボタンを表示</IonButton>
          ) : (
            <IonButton expand="block" color="medium" onClick={stopOverlay}>浮遊ボタンを停止</IonButton>
          )}

          <IonText color="medium">
            <p style={{ fontSize: 14, lineHeight: 1.7, marginTop: 24 }}>
              表示後、ホームに戻ると画面左上に赤い「＋」ボタンが出ます。
              タップすると画面中央にURL入力欄が開き（周囲は薄暗くなります）、
              ボタンは「−」に変わります。「−」または暗部をタップすると引っ込みます。
              ボタンをドラッグすると上部中央にゴミ箱が現れ、そこにドロップすると終了します。
            </p>
          </IonText>

          <div style={{ marginTop: 8, paddingTop: 16, borderTop: '1px solid #eee' }}>
            <IonButton expand="block" fill="outline" disabled={updating} onClick={updateYtdlp}>
              {updating ? '更新中…' : 'yt-dlp を更新'}
            </IonButton>
            {updateMsg && (
              <IonText color="medium">
                <p style={{ fontSize: 13, marginTop: 8, wordBreak: 'break-all' }}>{updateMsg}</p>
              </IonText>
            )}

            <div style={{
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
              marginTop: 16,
            }}>
              <span>cookies（X等の認証用）</span>
              <IonText color={cookies ? 'success' : 'medium'}>
                {cookies ? '設定済み' : '未設定'}
              </IonText>
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
              <IonButton style={{ flex: 1 }} fill="outline" onClick={importCookies}>
                cookies.txt をインポート
              </IonButton>
              <IonButton fill="clear" color="medium" disabled={!cookies} onClick={clearCookies}>
                削除
              </IonButton>
            </div>

            <IonText color="medium">
              <p style={{ fontSize: 12, lineHeight: 1.6, marginTop: 8 }}>
                X(Twitter)等で失敗する場合はまず「yt-dlp を更新」を。認証が必要な投稿は、
                ブラウザ拡張等で書き出した cookies.txt（Netscape形式）を上から取り込むと成功率が上がります。
              </p>
            </IonText>
          </div>
        </div>
      </IonContent>
    </IonApp>
  );
}
