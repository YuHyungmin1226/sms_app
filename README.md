# SMS App

Android 연락처 그룹에서 수신자를 선택해 SMS, 장문 문자, 이미지 MMS를 작성하는 Compose 앱입니다.

## 전송 방식

- SMS: `SmsManager.sendTextMessage`로 직접 전송합니다.
- 장문: `SmsManager.divideMessage`로 구간을 계산하고 `sendMultipartTextMessage`로 직접 전송합니다. 단말과 통신사에 따라 여러 SMS로 과금되거나 장문 문자로 처리될 수 있습니다.
- 이미지 MMS: 기본 문자 앱의 작성 화면을 열고 사용자가 최종 전송을 확인합니다. Android 공개 API만으로 통신사별 MMS PDU를 안정적으로 직접 생성하고 대량 발송하는 기능은 제공하지 않습니다.

## 권한

- `READ_CONTACTS`: 연락처와 그룹 조회
- `SEND_SMS`: SMS 및 장문 직접 전송

MMS 작성 화면은 설치된 기본 문자 앱을 사용합니다.

## 빌드

JDK 17 이상과 Android SDK가 필요합니다.

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```
