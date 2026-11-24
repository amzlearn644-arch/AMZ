# AMZ Racer — Unity 3D Car Game

A starter 3D arcade racing project for Unity. The scene is generated from the Unity Editor menu, so no paid assets are required.

## Requirements
- Unity 2022.3 LTS or newer
- Unity Hub with the 3D Core template
- For Android: Android Build Support, Android SDK & NDK Tools, and OpenJDK installed through Unity Hub

## Install
1. Clone this repository and open the `UnityCarGame` folder as a Unity project.
2. Let Unity import and compile scripts.
3. Select **AMZ Racer → Generate Demo Scene** from the top menu.
4. Press Play.

## Controls
- Keyboard: W/S or arrow keys to accelerate/reverse and steer; Space to brake.
- Touchscreen: on-screen left/right, brake and accelerator controls.

## Android build
1. In Unity, open File → Build Profiles (or Build Settings).
2. Select Android and switch platform.
3. Add `Assets/Scenes/RacerDemo.unity` to the build scenes.
4. Set a unique Package Name in Player Settings and build an APK.

This is a playable prototype foundation. It uses primitive meshes; polished vehicle models, sound, AI opponents, lap timing and progression can be added in later iterations.
