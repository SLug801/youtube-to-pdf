import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const electronDirectory = path.resolve(scriptDirectory, '..');
const repositoryDirectory = path.resolve(electronDirectory, '..');
const backendDirectory = path.join(repositoryDirectory, 'backend');
const pythonBackendDirectory = path.join(repositoryDirectory, 'python-backend');
const licensesPath = path.join(repositoryDirectory, 'THIRD-PARTY-LICENSES.txt');
const stageDirectory = path.join(electronDirectory, 'resources', 'backend');
const jarName = 'youtube-to-pdf-1.0.0-shaded.jar';
const jarPath = path.join(backendDirectory, 'build', 'libs', jarName);
const apiName = process.platform === 'win32' ? 'ytpdf-api.exe' : 'ytpdf-api';
const apiDirectory = path.join(pythonBackendDirectory, 'dist', 'ytpdf-api');
const apiPath = path.join(apiDirectory, apiName);
const shouldBuild = process.argv.includes('--build');
const isFile = (candidate) =>
  fs.statSync(candidate, { throwIfNoEntry: false })?.isFile() ?? false;

if (shouldBuild) {
  const buildEnvironment = { ...process.env };
  if (process.platform === 'darwin') {
    const javaHome = spawnSync('/usr/libexec/java_home', ['-v', '21'], {
      encoding: 'utf8',
      shell: false,
    });
    if (javaHome.status === 0 && javaHome.stdout.trim()) {
      buildEnvironment.JAVA_HOME = javaHome.stdout.trim();
    }
  }

  const command = process.platform === 'win32' ? 'cmd.exe' : './gradlew';
  const args = process.platform === 'win32'
    ? ['/d', '/s', '/c', 'gradlew.bat', 'test', 'shadowJar']
    : ['test', 'shadowJar'];
  const result = spawnSync(command, args, {
    cwd: backendDirectory,
    env: buildEnvironment,
    stdio: 'inherit',
    shell: false,
  });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }

  const apiBuild = spawnSync(
    process.platform === 'win32' ? 'uv.exe' : 'uv',
    [
      'run',
      '--project',
      pythonBackendDirectory,
      '--extra',
      'build',
      'pyinstaller',
      '--clean',
      '--noconfirm',
      '--name',
      'ytpdf-api',
      '--paths',
      'src',
      '--distpath',
      'dist',
      '--workpath',
      'build/pyinstaller',
      '--specpath',
      'build/pyinstaller',
      'src/ytpdf_api/__main__.py',
    ],
    {
      cwd: pythonBackendDirectory,
      stdio: 'inherit',
      shell: false,
    },
  );
  if (apiBuild.status !== 0) {
    console.error('[오류] FastAPI 실행 파일 빌드에 실패했습니다.');
    process.exit(apiBuild.status ?? 1);
  }
}

if (!isFile(jarPath)) {
  console.error(`[오류] 백엔드 JAR가 없습니다: ${jarPath}`);
  console.error('먼저 npm run build:backend를 실행해 주세요.');
  process.exit(1);
}
if (!isFile(apiPath)) {
  console.error(`[오류] FastAPI 실행 파일이 없습니다: ${apiPath}`);
  console.error('먼저 npm run build:backend를 실행해 주세요.');
  process.exit(1);
}

fs.rmSync(stageDirectory, { recursive: true, force: true });
fs.mkdirSync(stageDirectory, { recursive: true });
fs.copyFileSync(jarPath, path.join(stageDirectory, jarName));
fs.copyFileSync(licensesPath, path.join(stageDirectory, 'THIRD-PARTY-LICENSES.txt'));
fs.cpSync(apiDirectory, path.join(stageDirectory, 'api'), { recursive: true });
if (process.platform !== 'win32') {
  fs.chmodSync(path.join(stageDirectory, 'api', apiName), 0o755);
}

const ytDlpName = process.platform === 'win32' ? 'yt-dlp.exe' : 'yt-dlp';
const ytDlpPath = path.join(backendDirectory, ytDlpName);
if (isFile(ytDlpPath)) {
  fs.copyFileSync(ytDlpPath, path.join(stageDirectory, ytDlpName));
} else {
  console.warn(`[경고] ${ytDlpName}을 찾지 못해 패키지에 포함하지 않았습니다.`);
}

fs.writeFileSync(
  path.join(stageDirectory, 'manifest.json'),
  `${JSON.stringify(
    {
      jar: jarName,
      api: path.join('api', apiName),
      platform: process.platform,
      arch: process.arch,
      stagedAt: new Date().toISOString(),
    },
    null,
    2,
  )}\n`,
  'utf8',
);

console.log(`[완료] Electron 백엔드 리소스 준비: ${stageDirectory}`);
