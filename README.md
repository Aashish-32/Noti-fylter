# NotiFilter

NotiFilter is an Android application designed to give you granular control over your notifications. It allows you to filter, prioritize, and customize how you receive alerts from different apps, ensuring you never miss what's important while minimizing distractions.

## Features

- **App Priority Management**: Enable or disable custom notification handling for any installed application.
- **Custom Vibration Patterns**: Choose from several preset vibration patterns (Pulse, Heartbeat, SOS, etc.) or record your own custom pattern.
- **Visual Alerts**: Toggle camera flash notifications for specific apps.
- **Scheduled Silence**: Set active hours for notification enhancements to avoid being disturbed during sleep or work.
- **Notification History**: Keep a log of incoming notifications to review them later.
- **Messaging App Auto-Config**: Quickly set high priority for popular messaging apps like WhatsApp, Telegram, and Slack.
- **Dark Mode Support**: A clean UI that respects your system's dark mode settings.

## Getting Started

### Prerequisites

- Android Device running API 26 (Oreo) or higher.
- Notification Listener access (the app will prompt you to enable this).

### Installation

1. Clone the repository.
2. Open the project in Android Studio.
3. Build and run the app on your device or emulator.

## How to Use

1. **Grant Permission**: Upon first launch, tap the "Grant Permission" button to allow NotiFilter to see your notifications.
2. **Configure Apps**: Scroll through the list of installed apps and tap on one to customize its settings.
3. **Set Patterns**: Choose a vibration pattern and test it. You can even record a custom rhythm!
4. **Auto-Set**: Use the "Auto-Set Messaging" button to quickly apply optimized settings to known communication apps.
5. **Check History**: Use the navigation drawer to view the "History" and see a timeline of captured notifications.

## Permissions

- `BIND_NOTIFICATION_LISTENER_SERVICE`: To intercept and process notifications.
- `VIBRATE`: To provide haptic feedback.
- `CAMERA`: To use the flash for notification alerts.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

---
*Created as part of the NotiFilter project.*
