import React from 'react';
import './settings.css';

export type ThemePreference = 'system' | 'light' | 'dark';

export const THEME_STORAGE_KEY = 'score-lab-theme-v1';

export function readThemePreference(): ThemePreference {
  const saved = localStorage.getItem(THEME_STORAGE_KEY);
  return saved === 'light' || saved === 'dark' || saved === 'system'
    ? saved
    : 'system';
}

export function applyThemePreference(preference: ThemePreference): void {
  const resolved = preference === 'system'
    ? window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
    : preference;
  document.documentElement.dataset.theme = resolved;
  document.documentElement.style.colorScheme = resolved;
  localStorage.setItem(THEME_STORAGE_KEY, preference);
}

interface SettingsPageProps {
  theme: ThemePreference;
  onThemeChange: (theme: ThemePreference) => void;
}

export function SettingsPage({
  theme,
  onThemeChange,
}: SettingsPageProps): React.JSX.Element {
  return (
    <section className="settings-page">
      <div className="settings-page-heading">
        <p>환경 설정</p>
        <h2>앱의 화면과 동작을 설정합니다.</h2>
      </div>

      <div className="settings-grid">
        <section className="settings-card">
          <div className="settings-card-copy">
            <span>화면</span>
            <h3>테마</h3>
            <p>운영체제 설정을 따르거나 밝은 화면과 어두운 화면을 직접 선택합니다.</p>
          </div>
          <div className="theme-options" role="radiogroup" aria-label="화면 테마">
            {([
              ['system', '시스템', '자동'],
              ['light', '라이트', '밝게'],
              ['dark', '다크', '어둡게'],
            ] as const).map(([value, label, hint]) => (
              <button
                type="button"
                key={value}
                className={theme === value ? 'selected' : ''}
                role="radio"
                aria-checked={theme === value}
                onClick={() => onThemeChange(value)}
              >
                <span className={`theme-preview ${value}`} aria-hidden="true">
                  <i />
                  <i />
                  <i />
                </span>
                <strong>{label}</strong>
                <small>{hint}</small>
              </button>
            ))}
          </div>
        </section>

        <section className="settings-card settings-info-card">
          <div className="settings-card-copy">
            <span>정보</span>
            <h3>Score Lab</h3>
            <p>악보 영상 변환과 정밀 메트로놈을 하나의 데스크톱 작업 공간에서 제공합니다.</p>
          </div>
          <dl>
            <div><dt>앱 버전</dt><dd>0.1.0</dd></div>
            <div><dt>처리 엔진</dt><dd>FastAPI · Python Worker</dd></div>
            <div><dt>설정 저장</dt><dd>이 기기에만 저장</dd></div>
          </dl>
        </section>
      </div>
    </section>
  );
}
