---
name: blender-scripts
description: Write Blender Python (bpy) scripts in chat or project files that the user runs in desktop Blender. No live Blender control on the phone — this skill is script generation only, written by Hermes (not copied from any Blender MCP project).
---

# Blender scripts (phone equivalent: script generation only)

Hermes cannot run Blender on Android — there is no Blender for Android,
and no live Blender connection is claimed. What Hermes CAN do: write
correct, runnable Blender Python scripts that the user runs in desktop
Blender. Every script follows this shape:

## Script shape

1. Header comment: what the script builds, Blender version it targets
   (4.x API unless the user says otherwise), and how to run it
   (Blender Text editor → Run Script, or
   `blender --background --python script.py`).
2. Imports only from `bpy` and the standard library. No third-party
   packages unless the user confirms they are installed in their Blender.
3. Clean-slate guard: delete only objects the script itself created
   (by name prefix or a dedicated collection) — never wipe the user's
   whole scene by default. Ask before any destructive step.
4. Parameters at the top as plain variables (sizes, counts, materials)
   so the user can tweak without reading the whole script.
5. One `main()` that builds the scene, plus `if __name__ == "__main__":`
   guard behaviour appropriate for Blender's Text editor (top-level
   call, since `__name__` is `"__main__"` there too).
6. End with a short report printed to the console: objects created,
   collections used, and any manual step left (render settings,
   export path).

## Rules

- Use `bpy.ops` only where a data-API call is awkward; prefer
  `bpy.data` creation (meshes, materials, objects, collections) for
  reliability in background mode.
- Meshes: build with `mesh.from_pydata(vertices, edges, faces)` and
  call `mesh.update()`. Modifiers and materials via the data API.
- Animation: keyframes via `obj.keyframe_insert(data_path=..., frame=...)`.
- Never claim a render, export, or viewport result you did not run.
  The user runs the script on desktop and reports back; iterate from
  their console output.
- Deliver scripts as files in the project folder (`.py`) and show them
  in chat; offer a ZIP export via the existing DownloadsExporter path.

## When to use

When the user asks for a 3D scene, model, animation, or Blender
automation. Load this skill, write the script, and state plainly:
"Run this in desktop Blender — Hermes cannot run Blender on the phone."
