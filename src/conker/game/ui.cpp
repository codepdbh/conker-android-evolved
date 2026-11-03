// ImGui settings menu and fps readout, for GameInfo::ui and GameInfo::message (see game_info.cpp).
//
// The render thread draws the frame with app::UiLock held (vulkan.cpp RenderUi), and the window thread runs
// Message for every message after ImGui's. Draw must never make a blocking cross-thread call: it only posts.
// So the fps in the title is posted (kFpsTitle), and the window thread sets it.
//
// On the headless harness there is no ImGui and no window, so everything but the option globals compiles out;
// keyboard.cpp can still ask Open().
#include "common/ui.h"

#if __has_include(<imgui.h>) && !defined(XBE_HEADLESS)

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cwchar>
#include <string>
#include <vector>

#include <imgui.h>

#include "app/app.h"
#include "common/bulb.h"
#include "common/cutscenes.h"
#include "common/display.h"
#include "common/fur.h"
#include "common/grass.h"
#include "common/keyboard.h"
#include "common/lighting.h"
#include "common/mouse.h"
#include "common/reflection.h"
#include "common/rope.h"
#include "common/warp.h"
#include "common/water.h"
#include "core/input.h"
#include "kernel/kernel.h"

namespace ui {

bool g_fps_overlay = false;
bool g_fps_title = true;
uint32_t g_menu_language = 0;

namespace {

// the window owns the title; Draw posts this, Message (window thread) writes it
constexpr uint32_t kFpsTitle = app::kMsgApp + 0x40;

// virtual-key codes the menu reads
constexpr int kMouse1 = 0x01, kInsert = 0x2D, kEscape = 0x1B;

std::atomic<bool> g_open{false};

// the menu shows or goes; the game is held while it is up (kernel::Hold)
void SetOpen(bool open) {
    g_open.store(open);
    kernel::Hold(open);
}

// the menu shut from the render thread (its X, a warp): the window thread takes the cursor back as Message does
void Close() {
    SetOpen(false);
    app::Post(mouse::kRefresh);
}

std::atomic<double> g_fps{0.0};

// the display settings while the menu is editing them; copied back on Apply
app::DisplayMode pending_mode = app::DisplayMode::Borderless;
uint32_t pending_w = 0, pending_h = 0;

// the display's modes, asked once
const std::vector<app::Mode>& Modes() {
    static const std::vector<app::Mode> modes = app::DisplayModes();
    return modes;
}

// the keyboard action waiting for its new key, -1 none, whose and which of its keys; render thread only
int g_rebind = -1;
int g_rebind_player = 0;
int g_rebind_slot = 0;
// a rebind waits for every key to be up before it takes one, so the click that started it is not the key
bool g_rebind_armed = false;
// after a rebind, frames left until its key counts as let go: ImGui would otherwise take it as a click too (a
// mouse button is released to ImGui a frame or so after app::KeyDown says so)
int g_rebind_settle = 0;
constexpr int kSettleFrames = 3;

// a rebind is waiting, for the window thread: Escape then cancels it instead of shutting the menu
std::atomic<bool> g_rebinding{false};

// how long the menu's settings hold still before it saves them
constexpr double kSaveDelay = 0.5;

// the waiting row's Cancel was under the mouse last frame: a left click there cancels instead of binding Mouse 1
bool g_cancel_hovered = false;

bool AnyDown() {
    for (int vk = 1; vk < 0x100; vk++)
        if (keyboard::Bindable(vk) && app::KeyDown(vk)) return true;
    return false;
}

// the menu is up and an action is waiting: the first key or button pressed is its new one
void PollRebind() {
    struct Mirror {
        ~Mirror() { g_rebinding.store(g_rebind >= 0); }
    } mirror;
    g_rebind_settle = AnyDown() ? (g_rebind_settle ? kSettleFrames : 0) : std::max(g_rebind_settle - 1, 0);
    if (g_rebind < 0) return;
    if (app::KeyDown(kEscape)) {
        g_rebind = -1;
        g_rebind_settle = kSettleFrames;
        return;
    }
    if (!g_rebind_armed) {
        g_rebind_armed = !AnyDown();
        return;
    }
    for (int vk = 1; vk < 0x100; vk++) {
        if (!keyboard::Bindable(vk) || !app::KeyDown(vk)) continue;
        if (vk == kMouse1 && g_cancel_hovered) {
            g_rebind = -1;
            g_rebind_settle = kSettleFrames;
            return;
        }
        // a key drives one of the player's actions once: wherever it was, it swaps with the key it replaces (this
        // action's other slot included)
        int& target = keyboard::g_keys[g_rebind_player][g_rebind][g_rebind_slot];
        for (auto& keys : keyboard::g_keys[g_rebind_player])
            for (int& k : keys)
                if (&k != &target && k == vk) k = target;
        target = vk;
        g_rebind = -1;
        g_rebind_settle = kSettleFrames;
        return;
    }
}

// a hint under the setting before it: smaller, dimmed and wrapped, lined up with a checkbox's label
void Hint(const char* text, bool checkbox = false) {
    const ImGuiStyle& style = ImGui::GetStyle();
    const float indent = checkbox ? ImGui::GetFrameHeight() + style.ItemInnerSpacing.x : 0.0f;
    ImGui::PushFont(nullptr, std::max(std::round(style.FontSizeBase * 0.8f), 11.0f));
    ImGui::SetCursorPosY(ImGui::GetCursorPosY() - style.ItemSpacing.y * 0.5f);  // close to its setting
    if (indent > 0) ImGui::Indent(indent);
    ImGui::PushStyleColor(ImGuiCol_Text, style.Colors[ImGuiCol_TextDisabled]);
    ImGui::TextWrapped("%s", text);
    ImGui::PopStyleColor();
    if (indent > 0) ImGui::Unindent(indent);
    ImGui::PopFont();
}

// a percent slider with a Reset beside it, greyed out while the value is its default
void Slider(const char* label, uint32_t* v, uint32_t min, uint32_t max, uint32_t def) {
    ImGui::PushID(label);
    const float reset = ImGui::CalcTextSize("Reset").x + ImGui::GetStyle().FramePadding.x * 2.0f;
    ImGui::SetNextItemWidth(ImGui::CalcItemWidth() - reset - ImGui::GetStyle().ItemInnerSpacing.x);
    ImGui::SliderScalar("##slider", ImGuiDataType_U32, v, &min, &max, "%u");
    ImGui::SameLine(0, ImGui::GetStyle().ItemInnerSpacing.x);
    ImGui::BeginDisabled(*v == def);
    if (ImGui::Button("Reset")) *v = def;
    ImGui::EndDisabled();
    ImGui::SameLine(0, ImGui::GetStyle().ItemInnerSpacing.x);
    ImGui::TextUnformatted(label);
    ImGui::PopID();
}

// the device in a player's keyboard, mouse or controller slot; picking one another player has moves it here
void DeviceCombo(int player, input::Kind kind) {
    using input::Kind;
    const std::vector<input::Device> devices = input::Devices(kind);
    const std::string held = input::Assigned(player, kind);
    const char* all = kind == Kind::Keyboard ? "All other keyboards" : "All other mice";
    // a device no longer connected keeps its name, without the #n
    auto label = [&](const std::string& key) -> std::string {
        if (key.empty()) return "None";
        if (key == input::kAll) return all;
        for (const input::Device& d : devices)
            if (d.key == key) return d.label;
        return key.substr(0, key.rfind('#')) + " (not connected)";
    };
    // whose it is now, when another player's
    auto whose = [&](const std::string& key) -> std::string {
        const int p = input::Holder(kind, key);
        return p >= 0 && p != player ? "  (player " + std::to_string(p + 1) + ")" : "";
    };
    ImGui::PushID(int(kind));
    ImGui::SetNextItemWidth(-FLT_MIN);
    if (ImGui::BeginCombo("##device", label(held).c_str())) {
        auto option = [&](const std::string& key, const std::string& text) {
            if (ImGui::Selectable((text + whose(key) + "##" + key).c_str(), held == key)) input::Assign(player, kind, key);
        };
        option("", "None");
        if (kind != Kind::Controller) option(input::kAll, all);
        for (const input::Device& d : devices) option(d.key, d.label);
        ImGui::EndCombo();
    }
    if (kind != Kind::Controller && held == input::kAll && ImGui::IsItemHovered())
        ImGui::SetTooltip("Uses every %s not given to another player", kind == Kind::Keyboard ? "keyboard" : "mouse");
    ImGui::PopID();
}

// a player's devices, and whether new controllers find a player on their own (shared by all, under each tab)
void DrawDevices(int player) {
    ImGui::SeparatorText("Devices");
    for (input::Kind kind : {input::Kind::Keyboard, input::Kind::Mouse, input::Kind::Controller}) {
        ImGui::AlignTextToFramePadding();
        ImGui::TextUnformatted(kind == input::Kind::Keyboard ? "Keyboard" : kind == input::Kind::Mouse ? "Mouse" : "Controller");
        ImGui::SameLine(ImGui::GetContentRegionAvail().x * 0.2f);
        DeviceCombo(player, kind);
    }
    bool auto_assign = input::AutoAssign();
    if (ImGui::Checkbox("New controllers go to the first player without one", &auto_assign))
        input::SetAutoAssign(auto_assign);
}

// a player's mouse look
void DrawMouse(int player) {
    ImGui::SeparatorText("Mouse");
    ImGui::Checkbox("Mouse look", &mouse::g_look[player]);
    Hint("Move the mouse to look around. Press Esc or Alt+Tab to get the cursor back.", true);
    Slider("Horizontal %", &mouse::g_sensitivity[player], 10, 500, mouse::kDefaultSensitivity);
    Slider("Vertical %", &mouse::g_sensitivity_y[player], 10, 500, mouse::kDefaultSensitivity);
    ImGui::Checkbox("Invert Y", &mouse::g_invert_y[player]);
}

// a player's keys
void DrawKeys(int player) {
    if (g_rebind >= 0 && g_rebind_player != player) g_rebind = -1;  // another player's tab: its rebind is done
    PollRebind();
    g_cancel_hovered = false;  // the waiting row sets it again as it draws
    ImGui::SeparatorText("Keyboard");
    Hint("Each action can have two keys or mouse buttons. Click a slot, then press the key you want.");
    Hint("Right-click a slot to clear it. Picking a key that is already used swaps the two.");
    // a frame per part of the controller; the frame names the part, so a row only names its action
    struct Row {
        int action;
        const char* label;
    };
    struct Group {
        const char* title;
        std::vector<Row> rows;
    };
    using namespace keyboard;
    static const Group kGroups[] = {
        {"Left stick", {{kMoveUp, "Up"}, {kMoveDown, "Down"}, {kMoveLeft, "Left"}, {kMoveRight, "Right"},
                        {kLS, "Click"}}},
        {"Right stick (camera)", {{kCamUp, "Up"}, {kCamDown, "Down"}, {kCamLeft, "Left"}, {kCamRight, "Right"},
                                  {kRS, "Click"}}},
        {"D-pad", {{kDpadUp, "Up"}, {kDpadDown, "Down"}, {kDpadLeft, "Left"}, {kDpadRight, "Right"}}},
        {"Face buttons", {{kA, "A"}, {kB, "B"}, {kX, "X"}, {kY, "Y"}}},
        {"Shoulders and triggers", {{kLB, "LB (white)"}, {kRB, "RB (black)"}, {kLT, "LT"}, {kRT, "RT"}}},
        {"Start / Back", {{kStart, "Start"}, {kBack, "Back"}}},
    };
    // two columns, the groups in pairs: the sticks, d-pad and face buttons, shoulders and start / back
    if (!ImGui::BeginTable("##groups", 2, ImGuiTableFlags_SizingStretchSame)) return;
    for (const Group& g : kGroups) {
        ImGui::TableNextColumn();
        ImGui::PushID(g.title);
        ImGui::BeginChild("##group", ImVec2(0, 0), ImGuiChildFlags_Borders | ImGuiChildFlags_AutoResizeY);
        ImGui::SeparatorText(g.title);
        // label, the action's keys side by side, then its Reset; the keys line up down the frame
        const float spacing = ImGui::GetStyle().ItemInnerSpacing.x;
        const float reset = std::max(ImGui::CalcTextSize("Reset").x, ImGui::CalcTextSize("Cancel").x) +
                            ImGui::GetStyle().FramePadding.x * 2.0f;  // the same button, either label
        const float avail = ImGui::GetContentRegionAvail().x;
        const float column = ImGui::GetCursorPosX() + avail * 0.3f;
        const float key_w = std::max((avail * 0.7f - reset - spacing * kSlots) / kSlots, 1.0f);
        for (const Row& r : g.rows) {
            ImGui::PushID(r.action);
            ImGui::AlignTextToFramePadding();
            ImGui::TextUnformatted(r.label);
            ImGui::SameLine(column);
            for (int s = 0; s < kSlots; s++) {
                if (s) ImGui::SameLine(0, spacing);
                ImGui::PushID(s);
                const bool waiting = g_rebind == r.action && g_rebind_slot == s;
                if (ImGui::Button(waiting ? "press..." : KeyName(g_keys[player][r.action][s]), ImVec2(key_w, 0)) &&
                    !g_rebind_settle) {
                    g_rebind = r.action;
                    g_rebind_player = player;
                    g_rebind_slot = s;
                    g_rebind_armed = false;
                }
                // right click unbinds, but not while a rebind waits or has just taken its key: then the click is
                // Mouse 2 being bound, which PollRebind takes before this button sees it
                if (ImGui::IsItemClicked(ImGuiMouseButton_Right) && g_rebind < 0 && !g_rebind_settle)
                    g_keys[player][r.action][s] = 0;
                ImGui::PopID();
            }
            ImGui::SameLine(0, spacing);
            if (g_rebind == r.action) {
                // waiting: Reset becomes Cancel, which PollRebind honours when the left click lands on it
                ImGui::Button("Cancel", ImVec2(reset, 0));
                g_cancel_hovered = ImGui::IsItemHovered();
            } else {
                ImGui::BeginDisabled(IsDefault(player, r.action));
                if (ImGui::Button("Reset", ImVec2(reset, 0))) ResetKey(player, r.action);
                ImGui::EndDisabled();
                if (ImGui::IsItemHovered(ImGuiHoveredFlags_AllowWhenDisabled))
                    ImGui::SetTooltip("default: %s", ActionDefault(r.action).c_str());
            }
            ImGui::PopID();
        }
        ImGui::EndChild();
        ImGui::PopID();
    }
    ImGui::EndTable();
    if (ImGui::Button("Default keys")) {
        keyboard::ResetKeys(player);
        g_rebind = -1;
    }
}

// the Debug tab: warps to a checkpoint, a level or a cutscene (warp.cpp runs them on the main thread)
void DrawWarp() {
    static char filter[64] = "";
    static int checkpoint = -1;
    static int level = 0, spawn = 0, cut_level = 0, cut_index = 0;

    const int current = warp::CurrentLevel();
    const char* current_name = warp::LevelName(current);
    ImGui::Text("Level: %d %s", current, current_name ? current_name : "");
    const std::string status = warp::Status();
    if (!status.empty()) ImGui::TextDisabled("%s", status.c_str());
    Hint("Warning: these start a new game. Don't save afterwards over a save you want to keep.");

    ImGui::SeparatorText("Checkpoints");
    ImGui::InputTextWithHint("##filter", "filter by name or level", filter, sizeof filter);
    const std::vector<warp::Checkpoint>& list = warp::Checkpoints();
    auto matches = [](const std::string& s) {
        std::string a = s, b = filter;
        for (char& c : a) c = char(std::tolower(uint8_t(c)));
        for (char& c : b) c = char(std::tolower(uint8_t(c)));
        return a.find(b) != std::string::npos;
    };
    if (ImGui::BeginListBox("##checkpoints", ImVec2(-FLT_MIN, ImGui::GetTextLineHeightWithSpacing() * 10))) {
        for (int i = 0; i < int(list.size()); i++) {
            const warp::Checkpoint& c = list[size_t(i)];
            const char* ln = warp::LevelName(c.level);
            if (!ln) continue;  // the multiplayer ones, whose levels are another table's
            char row[192];
            std::snprintf(row, sizeof row, "%3d  %-20s spawn %-3d %s", i, ln, c.spawn, c.name.c_str());
            if (filter[0] && !matches(row)) continue;
            if (ImGui::Selectable(row, checkpoint == i, ImGuiSelectableFlags_AllowDoubleClick)) {
                checkpoint = i;
                if (ImGui::IsMouseDoubleClicked(0) && warp::GoCheckpoint(i)) Close();
            }
        }
        ImGui::EndListBox();
    }
    ImGui::BeginDisabled(checkpoint < 0);
    if (ImGui::Button("Start at checkpoint") && warp::GoCheckpoint(checkpoint)) Close();
    ImGui::EndDisabled();
    Hint("Or double-click one. Earlier checkpoints count as completed.");

    // the levels that exist, for the combos
    std::string items;
    std::vector<int> ids;
    for (int i = 0; i < warp::kLevels; i++)
        if (const char* n = warp::LevelName(i)) {
            items += std::to_string(i) + " " + n + '\0';
            ids.push_back(i);
        }
    items += '\0';
    auto level_combo = [&](const char* label, int* v) {
        int idx = int(std::find(ids.begin(), ids.end(), *v) - ids.begin());
        if (idx == int(ids.size())) idx = 0;
        ImGui::Combo(label, &idx, items.c_str());
        *v = ids[size_t(idx)];
    };

    ImGui::SeparatorText("Level");
    level_combo("Level##level", &level);
    ImGui::InputInt("Spawn", &spawn);
    spawn = std::clamp(spawn, 0, 255);
    if (ImGui::Button("Load level") && warp::GoLevel(level, spawn)) Close();
    Hint("Starts with no progress.");

    ImGui::SeparatorText("Cutscenes");
    int found_level = -1;
    const std::vector<int> found = warp::Cutscenes(&found_level);
    if (found_level >= 0) {
        const char* fn = warp::LevelName(found_level);
        ImGui::Text("In %s:", fn ? fn : "this level");
        if (found.empty()) ImGui::TextDisabled("none");
        for (size_t i = 0; i < found.size(); i++) {
            if (i % 10) ImGui::SameLine();
            if (ImGui::Button(std::to_string(found[i]).c_str()) && warp::PlayCutscene(found_level, found[i])) Close();
        }
    }
    level_combo("Level##cut", &cut_level);
    ImGui::InputInt("Cutscene", &cut_index);
    cut_index = std::clamp(cut_index, 0, 255);
    if (ImGui::Button("Play cutscene") && warp::PlayCutscene(cut_level, cut_index)) Close();
    Hint("Loads the level first. Nothing plays if that level doesn't have it.");
}

#ifdef __ANDROID__
// The menu on a phone: the whole screen, for a finger. Sections to the left, and each setting a row of big choices, with
// what a phone has no use for (window modes, resolutions, keyboard and mouse) left out.
constexpr ImVec4 kMenuBack(0.067f, 0.086f, 0.043f, 0.97f), kMenuButton(0.20f, 0.25f, 0.11f, 1.0f),
    kMenuAccent(1.0f, 0.694f, 0.239f, 1.0f), kMenuAccentDown(0.91f, 0.392f, 0.102f, 1.0f),
    kMenuOnAccent(0.141f, 0.063f, 0.016f, 1.0f), kMenuDim(0.725f, 0.753f, 0.659f, 1.0f);
bool g_menu_dragged = false;  // the finger scrolled: what it lifts from is not a tap

const char* Tr(const char* es, const char* en, const char* pt) {
    return g_menu_language == 1 ? en : g_menu_language == 2 ? pt : es;
}

bool MenuButton(const char* label, bool on, ImVec2 size) {
    ImGui::PushStyleColor(ImGuiCol_Button, on ? kMenuAccent : kMenuButton);
    // the same as at rest: a finger leaves no hover behind to show
    ImGui::PushStyleColor(ImGuiCol_ButtonHovered, on ? kMenuAccent : kMenuButton);
    ImGui::PushStyleColor(ImGuiCol_ButtonActive, kMenuAccentDown);
    ImGui::PushStyleColor(ImGuiCol_Text, on ? kMenuOnAccent : ImVec4(1, 1, 1, 1));
    const bool pressed = ImGui::Button(label, size);
    ImGui::PopStyleColor(4);
    return pressed && !g_menu_dragged;
}

void MenuHint(const char* text) {
    ImGui::PushStyleColor(ImGuiCol_Text, kMenuDim);
    ImGui::PushFont(nullptr, std::round(ImGui::GetFontSize() * 0.72f));
    ImGui::TextWrapped("%s", text);
    ImGui::PopFont();
    ImGui::PopStyleColor();
}

void MenuTitle(const char* text) {
    ImGui::Dummy(ImVec2(0, ImGui::GetFontSize() * 0.5f));
    ImGui::PushStyleColor(ImGuiCol_Text, kMenuAccent);
    ImGui::TextUnformatted(text);
    ImGui::PopStyleColor();
}

// a row of equal choices; at holds the one on, or -1 when the value is none of them
bool MenuChoice(const char* label, const char* const* names, int count, int& at, const char* hint = nullptr) {
    ImGui::PushID(label);
    ImGui::TextUnformatted(label);
    if (hint) MenuHint(hint);
    const float gap = ImGui::GetStyle().ItemInnerSpacing.x;
    const float width = (ImGui::GetContentRegionAvail().x - gap * float(count - 1)) / float(count);
    bool changed = false;
    for (int i = 0; i < count; i++) {
        if (i) ImGui::SameLine(0, gap);
        ImGui::PushID(i);
        if (MenuButton(names[i], i == at, ImVec2(width, 0))) {
            changed = i != at;
            at = i;
        }
        ImGui::PopID();
    }
    ImGui::PopID();
    ImGui::Dummy(ImVec2(0, ImGui::GetFontSize() * 0.3f));
    return changed;
}

void MenuSwitch(const char* label, bool* v, const char* hint = nullptr) {
    const char* const kNames[] = {Tr("No", "No", "Não"), Tr("Sí", "Yes", "Sim")};
    int at = *v ? 1 : 0;
    if (MenuChoice(label, kNames, 2, at, hint)) *v = at == 1;
}

// a percentage, from a few values; one from settings.toml between them lights none
void MenuPercent(const char* label, uint32_t* v, const uint32_t* values, const char* const* names, int count,
                 const char* hint = nullptr) {
    int at = -1;
    for (int i = 0; i < count; i++)
        if (values[i] == *v) at = i;
    if (MenuChoice(label, names, count, at, hint)) *v = values[at];
}

void MenuAmount(const char* label, uint32_t* v, const char* hint = nullptr) {
    static constexpr uint32_t kValues[] = {0, 25, 50, 75, 100};
    static const char* const kNames[] = {"0", "25", "50", "75", "100"};
    MenuPercent(label, v, kValues, kNames, 5, hint);
}

// settings a choice index picks a value of
void MenuValues(const char* label, uint32_t* v, const uint32_t* values, const char* const* names, int count) {
    MenuPercent(label, v, values, names, count);
}

void MenuGraphics(app::Settings& s) {
    MenuTitle(Tr("Imagen", "Picture", "Imagem"));
    static constexpr uint32_t kRates[] = {30, 60};
    const char* const kRateNames[] = {"30 (original)", "60"};
    int rate = display::g_frame_rate >= 60 ? 1 : 0;
    if (MenuChoice("FPS", kRateNames, 2, rate, Tr("60 exige bastante más al teléfono.", "60 asks far more of the phone.", "60 exige bem mais do telefone.")))
        display::SetFrameRate(kRates[rate]);
    static constexpr uint32_t kRender[] = {33, 50, 67, 75, 100, 150};
    static const char* const kRenderNames[] = {"33", "50", "67", "75", "100", "150"};
    MenuPercent(Tr("Resolución %", "Resolution %", "Resolução %"), &s.render_percent, kRender, kRenderNames, 6,
                Tr("Más nítido cuanto más alto, y más lento. 100 es la pantalla del teléfono.", "Sharper the higher, and slower. 100 is the phone's screen.", "Mais nítido quanto mais alto, e mais lento. 100 é a tela do telefone."));
    static const char* const kAspects[] = {"auto", "4:3", "16:9"};
    int aspect = -1;
    for (int i = 0; i < 3; i++)
        if (s.aspect == kAspects[i]) aspect = i;
    if (MenuChoice(Tr("Proporción", "Aspect", "Proporção"), kAspects, 3, aspect)) s.aspect = kAspects[aspect];
    static constexpr uint32_t kAniso[] = {0, 2, 4, 8, 16};
    static const char* const kAnisoNames[] = {Tr("Juego", "Game", "Jogo"), "2x", "4x", "8x", "16x"};
    MenuValues(Tr("Filtrado anisótropo", "Anisotropic filtering", "Filtragem anisotrópica"), &s.aniso, kAniso, kAnisoNames, 5);
    static constexpr uint32_t kMsaa[] = {1, 2, 4};
    const char* const kMsaaNames[] = {Tr("No", "No", "Não"), "2x", "4x"};
    MenuValues(Tr("Antialiasing (MSAA)", "Antialiasing (MSAA)", "Antialiasing (MSAA)"), &s.msaa, kMsaa, kMsaaNames, 3);

    MenuTitle(Tr("Posprocesado", "Post-processing", "Pós-processamento"));
    MenuSwitch("SMAA", &s.smaa);
    MenuAmount(Tr("Grano de película %", "Film grain %", "Granulação %"), &s.film_grain);
    MenuAmount(Tr("Aberración cromática %", "Chromatic aberration %", "Aberração cromática %"), &s.chromatic_aberration);
    MenuAmount(Tr("Oclusión ambiental %", "Ambient occlusion %", "Oclusão ambiente %"), &s.ambient_occlusion);
}

void MenuEnhancements() {
    MenuTitle(Tr("Luz y pelo", "Light and fur", "Luz e pelo"));
    MenuSwitch(Tr("Luz por píxel", "Per-pixel lighting", "Luz por pixel"), &lighting::g_per_pixel, Tr("Luces y sombras más suaves en personajes y objetos.", "Smoother light and shadows on characters and objects.", "Luzes e sombras mais suaves em personagens e objetos."));
    MenuSwitch(Tr("Pelo sombreado", "Fur shading", "Pelo sombreado"), &fur::g_shading, Tr("Pelo más suave y natural.", "Softer, more natural fur.", "Pelo mais suave e natural."));
    MenuSwitch(Tr("Pelo completo a distancia", "Full distant fur", "Pelo completo à distância"), &fur::g_distant, Tr("El pelo sigue denso a lo lejos.", "Fur stays thick far away.", "O pelo continua denso ao longe."));

    MenuTitle(Tr("Hierba y agua", "Grass and water", "Grama e água"));
    MenuSwitch(Tr("Viento en la hierba lejana", "Wind on distant grass", "Vento na grama distante"), &grass::g_wind);
    MenuSwitch(Tr("Hierba densa a distancia", "Thick distant grass", "Grama densa à distância"), &grass::g_max_lod, Tr("La hierba lejana no pierde densidad.", "Faraway grass stays thick.", "A grama distante não perde densidade."));
    MenuSwitch(Tr("Desvanecer hierba lejana", "Fade distant grass", "Esmaecer grama distante"), &grass::g_fade, Tr("Como en la consola.", "As on the console.", "Como no console."));
    MenuSwitch(Tr("Agua dinámica", "Dynamic water", "Água dinâmica"), &water::g_dynamic, Tr("El agua hace olas cuando algo la atraviesa.", "Water makes waves as things move through it.", "A água faz ondas quando algo a atravessa."));
    MenuSwitch(Tr("Profundidad en suelos", "Floor depth of field", "Profundidade nos pisos"), &reflection::g_floor_depth, Tr("Corrige el desenfoque de los suelos brillantes.", "Fixes the blur on shiny floors.", "Corrige o desfoque dos pisos brilhantes."));

    MenuTitle(Tr("Juego", "Game", "Jogo"));
    MenuSwitch(Tr("Saltar cinemáticas", "Skippable cutscenes", "Pular cenas"), &cutscenes::g_skippable, Tr("LT salta cualquier cinemática.", "LT skips any cutscene.", "LT pula qualquer cena."));
    MenuSwitch(Tr("Bombilla duradera", "Lasting idea bulb", "Lâmpada duradoura"), &bulb::g_lasting, Tr("La bombilla sigue mientras Conker esté sobre un botón B.", "The bulb stays while Conker stands on a B pad.", "A lâmpada fica enquanto Conker estiver sobre um botão B."));
    MenuSwitch(Tr("Física de cuerdas", "Rope physics", "Física das cordas"), &rope::g_physics);
}

void MenuSound(app::Settings& s) {
    MenuTitle(Tr("Sonido", "Sound", "Som"));
    MenuAmount(Tr("Volumen %", "Volume %", "Volume %"), &s.volume);
    MenuSwitch(Tr("Silenciar en segundo plano", "Mute in background", "Silenciar em segundo plano"), &s.mute_in_background);
}

void MenuTools() {
    MenuTitle(Tr("Herramientas", "Tools", "Ferramentas"));
    MenuSwitch(Tr("Mostrar FPS", "Show FPS", "Mostrar FPS"), &g_fps_overlay);
    const char* const kLanguages[] = {"Español", "English", "Português"};
    int language = int(g_menu_language);
    if (MenuChoice(Tr("Idioma del menú", "Menu language", "Idioma do menu"), kLanguages, 3, language))
        g_menu_language = uint32_t(language);
    DrawWarp();
}

void DrawTouchMenu(app::Settings& s) {
    const ImVec2 display = ImGui::GetIO().DisplaySize;
    const float font = std::max(std::round(display.y / 26.0f), 20.0f);
    const float margin = font * 1.2f;
    ImGui::SetNextWindowPos(ImVec2(0, 0));
    ImGui::SetNextWindowSize(display);
    ImGui::PushFont(nullptr, font);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowRounding, 0);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowBorderSize, 0);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(margin, font * 0.7f));
    ImGui::PushStyleVar(ImGuiStyleVar_FramePadding, ImVec2(font * 0.5f, font * 0.55f));
    ImGui::PushStyleVar(ImGuiStyleVar_FrameRounding, font * 0.4f);
    ImGui::PushStyleVar(ImGuiStyleVar_ItemSpacing, ImVec2(font * 0.5f, font * 0.4f));
    ImGui::PushStyleVar(ImGuiStyleVar_ItemInnerSpacing, ImVec2(font * 0.3f, font * 0.3f));
    ImGui::PushStyleVar(ImGuiStyleVar_ScrollbarSize, font * 0.35f);
    ImGui::PushStyleColor(ImGuiCol_WindowBg, kMenuBack);
    ImGui::PushStyleColor(ImGuiCol_ChildBg, ImVec4(0, 0, 0, 0));
    if (ImGui::Begin("##menu", nullptr, ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoSavedSettings)) {
        static int page = 0;
        const char* const kPages[] = {Tr("Gráficos", "Graphics", "Gráficos"), Tr("Mejoras", "Enhancements", "Melhorias"), Tr("Sonido", "Sound", "Som"), Tr("Herramientas", "Tools", "Ferramentas")};
        const float side = std::min(display.x * 0.24f, font * 10.0f);
        const float row = ImGui::GetFrameHeight();
        ImGui::BeginChild("##side", ImVec2(side, 0));
        for (int i = 0; i < 4; i++)
            if (MenuButton(kPages[i], i == page, ImVec2(-1, row * 1.2f))) page = i;
        // to the bottom of the column
        const float foot = row * 2.0f + ImGui::GetStyle().ItemSpacing.y;
        ImGui::SetCursorPosY(std::max(ImGui::GetCursorPosY(), ImGui::GetContentRegionMax().y - foot));
        if (MenuButton(Tr("Volver al juego", "Back to the game", "Voltar ao jogo"), true, ImVec2(-1, row))) Close();
        if (MenuButton("Salir del juego", false, ImVec2(-1, row))) ImGui::OpenPopup("##quit");
        ImGui::SetNextWindowPos(ImVec2(display.x * 0.5f, display.y * 0.5f), ImGuiCond_Always, ImVec2(0.5f, 0.5f));
        ImGui::SetNextWindowSize(ImVec2(std::min(display.x * 0.6f, font * 24.0f), 0));
        if (ImGui::BeginPopupModal("##quit", nullptr, ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoMove)) {
            ImGui::TextWrapped(Tr("¿Salir del juego? Se pierde lo que no hayas guardado.", "Quit the game? What you haven't saved is lost.", "Sair do jogo? O que não foi salvo será perdido."));
            const float half = (ImGui::GetContentRegionAvail().x - ImGui::GetStyle().ItemSpacing.x) * 0.5f;
            if (MenuButton(Tr("Salir", "Quit", "Sair"), false, ImVec2(half, 0))) app::Quit();  // only posts: the window thread closes
            ImGui::SameLine();
            if (MenuButton(Tr("Seguir jugando", "Keep playing", "Continuar jogando"), true, ImVec2(half, 0))) ImGui::CloseCurrentPopup();
            ImGui::EndPopup();
        }
        ImGui::EndChild();

        ImGui::SameLine(0, margin);
        ImGui::BeginChild("##page", ImVec2(0, 0));
        switch (page) {
            case 0: MenuGraphics(s); break;
            case 1: MenuEnhancements(); break;
            case 2: MenuSound(s); break;
            default: MenuTools(); break;
        }
        // ImGui scrolls by wheel and bar: here by the finger, anywhere on the page
        const ImGuiIO& io = ImGui::GetIO();
        if (!io.MouseDown[0]) {
            g_menu_dragged = false;
        } else if (ImGui::IsWindowHovered(ImGuiHoveredFlags_ChildWindows | ImGuiHoveredFlags_AllowWhenBlockedByActiveItem)) {
            if (ImGui::IsMouseDragging(ImGuiMouseButton_Left, font * 0.4f)) g_menu_dragged = true;
            if (g_menu_dragged) ImGui::SetScrollY(ImGui::GetScrollY() - io.MouseDelta.y);
        }
        ImGui::EndChild();
    }
    ImGui::End();
    ImGui::PopStyleColor(2);
    ImGui::PopStyleVar(8);
    ImGui::PopFont();
}
#endif

}  // namespace

void Draw(double fps) {
    // the menu's pending display settings follow the live ones each time it opens
    static bool was_open = false;
    static double last_fps = -1.0;
    static bool last_title = !g_fps_title;
    static int last_look = -1;
    const bool open = g_open.load();
    if (open && !was_open) {
        pending_mode = app::Get().display_mode;
        pending_w = app::Get().width;
        pending_h = app::Get().height;
    }
    if (!open) g_rebind = -1;  // closing the menu cancels a rebind

    // the menu saves itself: once its settings have held still for kSaveDelay (not every frame of a slider's drag),
    // and as it shuts
    static std::string seen;
    static double seen_at = 0.0;
    if (open) {
        std::string now = app::SettingsText();
        const double t = ImGui::GetTime();
        if (now != seen) seen = std::move(now), seen_at = t;
        else if (t - seen_at >= kSaveDelay) app::SaveChanged();
    } else if (was_open) {
        app::SaveChanged();
    }
    was_open = open;

    // not from here: setting the title from the render thread could deadlock on the window thread's UiLock; post
    if (fps != last_fps || g_fps_title != last_title) {
        last_fps = fps;
        last_title = g_fps_title;
        g_fps.store(fps);
        app::Post(kFpsTitle);
    }

    // the window thread takes or lets go of the cursor, as a player with a mouse turns mouse look on or off, or gets
    // or loses the mouse
    int look = 0;
    for (int p = 0; p < input::kPorts; p++)
        if (mouse::g_look[p] && input::Has(p, input::Kind::Mouse)) look |= 1 << p;
    if (look != last_look) {
        last_look = look;
        app::Post(mouse::kRefresh);
    }

    if (g_fps_overlay) {
        ImGui::SetNextWindowPos(ImVec2(10.0f, 10.0f), ImGuiCond_Always);
        ImGui::SetNextWindowBgAlpha(0.35f);
        const ImGuiWindowFlags flags =
            ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoInputs | ImGuiWindowFlags_AlwaysAutoResize |
            ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoFocusOnAppearing | ImGuiWindowFlags_NoNav;
        if (ImGui::Begin("##fps", nullptr, flags))
            ImGui::Text("%.0f FPS (%.2f ms)", fps, fps > 0.0 ? 1000.0 / fps : 0.0);
        ImGui::End();
    }

    if (!open) return;

    app::Settings& s = app::Get();
#ifdef __ANDROID__
    DrawTouchMenu(s);
    return;
#endif
    // first opened (there is no imgui.ini, so each launch): about half the display, centred, never smaller than the
    // keyboard bindings need nor larger than the display
    const ImVec2 display = ImGui::GetIO().DisplaySize;
    const float scale = ImGui::GetFontSize() / 13.0f;  // the minimums were set with ImGui's 13 px font
    const ImVec2 size(std::min(std::max(display.x * 0.45f, 720.0f * scale), display.x),
                      std::min(std::max(display.y * 0.65f, 640.0f * scale), display.y));
    ImGui::SetNextWindowSize(size, ImGuiCond_FirstUseEver);
    ImGui::SetNextWindowPos(ImVec2(display.x * 0.5f, display.y * 0.5f), ImGuiCond_FirstUseEver, ImVec2(0.5f, 0.5f));
    bool keep_open = true;
    if (ImGui::Begin("Settings", &keep_open)) {
        bool keys_shown = false;
        if (ImGui::BeginTabBar("##tabs")) {
            // each tab scrolls on its own above the footer line
            const float footer = ImGui::GetFrameHeightWithSpacing() + ImGui::GetStyle().ItemSpacing.y * 2.0f;
            auto tab = [&](const char* name) {
                if (!ImGui::BeginTabItem(name)) return false;
                ImGui::BeginChild("##page", ImVec2(0, -footer));
                return true;
            };
            auto end_tab = [] {
                ImGui::EndChild();
                ImGui::EndTabItem();
            };

            if (tab("Graphics")) {
                ImGui::SeparatorText("Display");
                int mode = int(pending_mode);
                if (ImGui::Combo("Mode", &mode, "Fullscreen\0Borderless\0Windowed\0"))
                    pending_mode = app::DisplayMode(mode);

                const std::vector<app::Mode>& modes = Modes();
                std::string res = "Desktop";
                int res_idx = 0;
                for (size_t i = 0; i < modes.size(); i++) {
                    res += '\0';
                    res += std::to_string(modes[i].width) + "x" + std::to_string(modes[i].height);
                    if (modes[i].width == pending_w && modes[i].height == pending_h) res_idx = int(i) + 1;
                }
                res += '\0';
                if (ImGui::Combo("Resolution", &res_idx, res.c_str())) {
                    if (res_idx == 0) pending_w = pending_h = 0;
                    else pending_w = modes[res_idx - 1].width, pending_h = modes[res_idx - 1].height;
                }
                if (ImGui::Button("Apply display")) {
                    s.display_mode = pending_mode;
                    s.width = pending_w;
                    s.height = pending_h;
                    app::ApplyDisplay();
                }
                // the camera follows the setting live; only the TV type the console reports waits for a restart
                const char* aspects[] = {"auto", "4:3", "16:10", "16:9", "21:9", "32:9"};
                constexpr int kAspects = 6;
                int aspect = -1;
                for (int i = 0; i < kAspects; i++)
                    if (s.aspect == aspects[i]) aspect = i;
                std::string items;
                for (int i = 0; i < kAspects; i++) items += std::string(aspects[i]) + '\0';
                if (aspect < 0) items += s.aspect + '\0', aspect = kAspects;  // a custom W:H from settings.toml
                items += '\0';
                if (ImGui::Combo("Aspect", &aspect, items.c_str()) && aspect < kAspects) s.aspect = aspects[aspect];

                ImGui::SeparatorText("Quality");
                Slider("Render %", &s.render_percent, 25, 400, app::Settings{}.render_percent);
                ImGui::Checkbox("V-Sync", &s.vsync);

                constexpr uint32_t kRates[] = {30, 60, 120, 180, 240};
                int rate = 0;
                for (int i = 0; i < 5; i++)
                    if (kRates[i] == display::g_frame_rate) rate = i;
                if (ImGui::Combo("Frame rate", &rate, "30 (original)\0 60\0 120\0 180\0 240\0"))
                    display::SetFrameRate(kRates[rate]);

                constexpr uint32_t kAniso[] = {0, 2, 4, 8, 16};
                int aniso = 0;
                for (int i = 0; i < 5; i++)
                    if (kAniso[i] == s.aniso) aniso = i;
                if (ImGui::Combo("Anisotropic", &aniso, "Game default\0 2x\0 4x\0 8x\0 16x\0"))
                    s.aniso = kAniso[aniso];

                constexpr uint32_t kMsaa[] = {1, 2, 4, 8};
                int msaa = 0;
                for (int i = 0; i < 4; i++)
                    if (kMsaa[i] == s.msaa) msaa = i;
                if (ImGui::Combo("MSAA", &msaa, "Off\0 2x\0 4x\0 8x\0")) s.msaa = kMsaa[msaa];

                ImGui::SeparatorText("Post-processing");
                ImGui::Checkbox("SMAA", &s.smaa);
                Slider("Film grain %", &s.film_grain, 0, 100, app::Settings{}.film_grain);
                Slider("Chromatic aberration %", &s.chromatic_aberration, 0, 100, app::Settings{}.chromatic_aberration);
                Slider("Ambient occlusion %", &s.ambient_occlusion, 0, 100, app::Settings{}.ambient_occlusion);
                if (s.ambient_occlusion)
                    Slider("Ambient occlusion reach", &s.ambient_occlusion_radius, 5, 200,
                           app::Settings{}.ambient_occlusion_radius);
                end_tab();
            }

            if (tab("Enhancements")) {
                ImGui::SeparatorText("Lighting");
                ImGui::Checkbox("Per-pixel lighting", &lighting::g_per_pixel);
                Hint("Smoother light and shadows on characters and objects.", true);

                ImGui::SeparatorText("Fur");
                ImGui::Checkbox("Fur shading", &fur::g_shading);
                Hint("Softer, more natural-looking fur.", true);
                ImGui::Checkbox("Full distant fur", &fur::g_distant);
                Hint("Fur stays thick far away instead of thinning out.", true);

                ImGui::SeparatorText("Grass");
                ImGui::Checkbox("Wind on distant grass", &grass::g_wind);
                Hint("Faraway grass sways in the wind too.", true);
                ImGui::Checkbox("Force max grass LoD", &grass::g_max_lod);
                Hint("Faraway grass stays as thick as nearby grass instead of thinning out.", true);
                ImGui::Checkbox("Fade distant grass", &grass::g_fade);
                Hint("Faraway grass fades away as on the console. Off: it stays solid, however far.", true);

                ImGui::SeparatorText("Water and reflections");
                ImGui::Checkbox("Dynamic water", &water::g_dynamic);
                Hint("Water ripples and makes waves as things move through it.", true);
                ImGui::Checkbox("Floor depth of field", &reflection::g_floor_depth);
                Hint("Fixes the blur on shiny, reflective floors.", true);

                ImGui::SeparatorText("Gameplay");
                ImGui::Checkbox("Skippable cutscenes", &cutscenes::g_skippable);
                Hint("Press LT to skip any cutscene, even ones you haven't seen.", true);
                ImGui::Checkbox("Lasting idea bulb", &bulb::g_lasting);
                Hint("The lightbulb over Conker stays as long as he stands on a B pad, not just 2 seconds.", true);
                ImGui::Checkbox("Rope physics", &rope::g_physics);
                Hint("Ropes swing realistically when Conker hangs on them.", true);
                int split = display::g_side_by_side;
                if (ImGui::Combo("Two player split", &split, "Top and bottom\0Side by side (experimental)\0"))
                    display::g_side_by_side = split != 0;
                Hint(split ? "Starts from the next match. Some on-screen info may be in the wrong place."
                                          : "Starts from the next match.");
                end_tab();
            }

            if (tab("Audio")) {
                Slider("Volume %", &s.volume, 0, 100, app::Settings{}.volume);
                ImGui::Checkbox("Mute in background", &s.mute_in_background);
                Hint("No sound while you are in another window.", true);
                end_tab();
            }

            if (tab("Controls")) {
                if (ImGui::BeginTabBar("##player")) {
                    for (int p = 0; p < input::kPorts; p++) {
                        const std::string name = "Player " + std::to_string(p + 1);
                        if (!ImGui::BeginTabItem(name.c_str())) continue;
                        ImGui::PushID(p);
                        DrawDevices(p);
                        DrawMouse(p);
                        DrawKeys(p);
                        keys_shown = true;
                        ImGui::PopID();
                        ImGui::EndTabItem();
                    }
                    ImGui::EndTabBar();
                }
                end_tab();
            }

            if (tab("Debug")) {
                ImGui::SeparatorText("FPS");
                ImGui::Checkbox("Overlay", &g_fps_overlay);
                ImGui::SameLine();
                ImGui::Checkbox("Window title", &g_fps_title);
                DrawWarp();
                end_tab();
            }
            ImGui::EndTabBar();
        }
        if (!keys_shown) g_rebind = -1;  // leaving the Controls tab cancels a rebind

        ImGui::Separator();
        ImGui::AlignTextToFramePadding();
        ImGui::TextDisabled("Press Esc to open or close this menu. Changes are saved automatically.");
        const char* quit = "Quit game";
        ImGui::SameLine(ImGui::GetContentRegionMax().x - ImGui::CalcTextSize(quit).x -
                        ImGui::GetStyle().FramePadding.x * 2.0f);
        if (ImGui::Button(quit)) ImGui::OpenPopup("Quit?");
        if (ImGui::BeginPopupModal("Quit?", nullptr, ImGuiWindowFlags_AlwaysAutoResize)) {
            ImGui::TextUnformatted("Quit the game? Progress since the last save is lost.");
            if (ImGui::Button("Quit")) app::Quit();  // only posts: the window thread closes
            ImGui::SameLine();
            if (ImGui::Button("Cancel")) ImGui::CloseCurrentPopup();
            ImGui::EndPopup();
        }
    }
    ImGui::End();
    if (!keep_open) Close();  // the window's X
}

bool Message(void* hwnd, uint32_t msg, uintptr_t wp, intptr_t lp, intptr_t* result) {
    switch (msg) {
        case app::kMsgKeyDown:
            // not on auto repeat; the menu window and the game both take the key, the menu only when open
            // Escape cancels a rebind waiting for its key instead (PollRebind)
            if ((wp == kEscape || wp == kInsert) && !(lp & (1 << 30)) && !(wp == kEscape && g_rebinding.load())) {
                SetOpen(!g_open.load());
                mouse::Refresh(hwnd);  // the cursor is free while the menu is up
                if (result) *result = 0;
                return true;
            }
            break;
        case kFpsTitle: {
            wchar_t title[256];
            const int instance = app::Instance();
            if (g_fps_title) {
                if (instance)
                    swprintf(title, 256, L"%ls [%d] | %.0f FPS", app::kGame.title, instance, g_fps.load());
                else
                    swprintf(title, 256, L"%ls | %.0f FPS", app::kGame.title, g_fps.load());
            } else if (instance) {
                swprintf(title, 256, L"%ls [%d]", app::kGame.title, instance);
            } else {
                swprintf(title, 256, L"%ls", app::kGame.title);
            }
            app::SetTitle(title);
            if (result) *result = 0;
            return true;
        }
        default:
            break;
    }
    mouse::Message(hwnd, msg, wp, lp);
    return false;
}

bool Open() { return g_open.load(); }

}  // namespace ui

#else  // no ImGui here (the headless harness): the menu and the hook do nothing

namespace ui {

bool g_fps_overlay = false;
bool g_fps_title = true;
uint32_t g_menu_language = 0;

void Draw(double) {}

bool Message(void*, uint32_t, uintptr_t, intptr_t, intptr_t*) { return false; }

bool Open() { return false; }

}  // namespace ui

#endif
