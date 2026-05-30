import { registerPlugin } from '@capacitor/core';

export interface OverlayPlugin {
  checkPermission(): Promise<{ granted: boolean }>;
  requestPermission(): Promise<void>;
  start(): Promise<void>;
  stop(): Promise<void>;
  isRunning(): Promise<{ running: boolean }>;
  /** 同梱 yt-dlp を最新へ更新 */
  update(): Promise<{ result: string }>;
  /** cookies.txt を選択して取り込む（X等の認証用） */
  importCookies(): Promise<{ ok: boolean; path: string }>;
  /** cookies.txt が設定済みか */
  hasCookies(): Promise<{ present: boolean }>;
  /** cookies.txt を削除 */
  clearCookies(): Promise<void>;
}

export const Overlay = registerPlugin<OverlayPlugin>('Overlay');
