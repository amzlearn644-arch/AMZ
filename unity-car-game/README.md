# AMZ Racer — Unity 3D Car Game

A starter 3D arcade racing prototype for Android and desktop. The game scene is generated inside Unity by an Editor menu command; no paid assets are required.

## Included
- Third-person chase camera
- Arcade-style acceleration, reverse, steering, grip and braking
- Oval asphalt circuit and start/finish stripe
- Simple blue sports car built from 3D primitives
- Mobile touch buttons and speedometer
- Keyboard controls for testing in the Unity Editor

## Add to a Unity project
1. Install Unity 2022.3 LTS or newer using Unity Hub.
2. Create a **3D Core** project (Built-in Render Pipeline is the simplest starting point).
3. Copy this folder's `Assets` directory into the Unity project's root.
4. Wait for scripts to compile.
5. In Unity's top menu select **AMZ Racer → Create Racing Scene**.
6. Press Play. The generated scene is saved as `Assets/Scenes/AMZRacer.unity`.

## Controls
- Keyboard: W/Up = accelerate, S/Down = reverse, A/Left and D/Right = steer, Space = brake.
- Touch: LEFT, RIGHT, BRAKE and GAS buttons are generated in the scene.

## Android build
1. In Unity Hub, add **Android Build Support**, **Android SDK & NDK Tools**, and **OpenJDK** to your Unity installation.
2. Open File → Build Profiles (or Build Settings in older Unity), select Android, then Switch Platform.
3. Add `Assets/Scenes/AMZRacer.unity` to the build scenes.
4. In Player Settings set a unique Package Name, choose portrait/landscape orientation (landscape recommended), then Build.

## Current scope
This is a playable prototype foundation, not yet a finished commercial game. It deliberately uses primitive geometry and built-in UI to keep the project lightweight. Next production steps would be proper car and environment models, engine/skid audio, menus, lap/checkpoint system, opponents, settings and mobile performance profiling.
