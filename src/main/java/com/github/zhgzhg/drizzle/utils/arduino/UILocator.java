package com.github.zhgzhg.drizzle.utils.arduino;

import com.github.zhgzhg.drizzle.Drizzle;
import processing.app.Base;
import processing.app.Editor;
import processing.app.EditorConsole;
import processing.app.EditorStatus;
import processing.app.I18n;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class UILocator {
    public static final String BOARD_MENU_PREFIX_LABEL = "Board: ";
    private final Editor editor;

    public UILocator(Editor editor) {
        this.editor = editor;
    }

    public Optional<EditorConsole> editorConsole() {
        return Stream.of(editor.getContentPane().getComponents())
                .filter(c -> c instanceof JPanel)
                .flatMap(c -> Stream.of(((JPanel) c).getComponents()))
                .filter(c -> c instanceof Box)
                .flatMap(c -> Stream.of(((Box) c).getComponents()))
                .filter(c -> c instanceof JSplitPane)
                .flatMap(c -> Stream.of(((JSplitPane) c).getComponents()))
                .filter(c -> c instanceof JPanel)
                .flatMap(c -> Stream.of(((JPanel) c).getComponents()))
                .filter(c -> c instanceof EditorConsole)
                .map(c -> (EditorConsole) c)
                .findFirst();
    }

    public Optional<EditorStatus> editorStatus() {
        return Stream.of(editor.getContentPane().getComponents())
                .filter(c -> c instanceof JPanel)
                .flatMap(c -> Stream.of(((JPanel) c).getComponents()))
                .filter(c -> c instanceof Box)
                .flatMap(c -> Stream.of(((Box) c).getComponents()))
                .filter(c -> c instanceof JSplitPane)
                .flatMap(c -> Stream.of(((JSplitPane) c).getComponents()))
                .filter(c -> c instanceof JPanel)
                .flatMap(c -> Stream.of(((JPanel) c).getComponents()))
                .filter(c -> c instanceof EditorStatus)
                .map(c -> (EditorStatus) c)
                .findFirst();
    }

    public Optional<JMenuItem> drizzleMenu() {
        return Stream.of(editor.getContentPane().getParent().getComponents())
                .filter(c -> c instanceof JMenuBar)
                .flatMap(jb -> Stream.of(((JMenuBar) jb).getMenu(3).getMenuComponents()))
                .filter(c -> c instanceof JMenuItem && Drizzle.MENUS_HOLDER_TITLE.equals(((JMenuItem)c).getText()))
                .map(c -> (JMenuItem) c)
                .findFirst();
    }

    public Optional<JMenu> boardsMenu() {
        return Base.INSTANCE.getBoardsCustomMenus().stream()
                .filter(menu -> menu.getText().startsWith(I18n.tr(BOARD_MENU_PREFIX_LABEL)))
                .findFirst();
    }

    public Optional<Container> boardsMenuParent() {
        return Base.INSTANCE.getBoardsCustomMenus().stream()
                .filter(menu -> menu.getText().startsWith(I18n.tr(BOARD_MENU_PREFIX_LABEL)))
                .map(JMenu::getParent)
                .findFirst();
    }

    public List<JMenu> customMenusForCurrentlySelectedBoard() {
        return Base.INSTANCE.getBoardsCustomMenus().stream()
                .filter(jm -> jm.isEnabled() && jm.isVisible())
                .collect(Collectors.toList());
    }

    public JMenu getMenuByName(JMenuBar from, String name) {
        if (from != null) {
            for (int i = 0; i < from.getMenuCount(); ++i) {
                String menuName = from.getMenu(i).getText();

                if (Objects.equals(name, menuName)) {
                    return from.getMenu(i);
                }
            }
        }

        return null;
    }

    public Optional<JMenu> sketchIncludeLibraryMenu() {
        return Stream.of(editor.getContentPane().getParent().getComponents())
                .filter(c -> c instanceof JMenuBar)
                .map(c -> getMenuByName((JMenuBar) c, I18n.tr("Sketch")))
                .filter(Objects::nonNull)
                .flatMap(jm -> Stream.of(jm.getMenuComponents()))
                .filter(c -> c instanceof JMenu && I18n.tr("Include Library").equals(((JMenu) c).getText()))
                .map(jm -> (JMenu) jm)
                .findFirst();
    }

    public Optional<JMenu> filesExamplesMenu() {
        return Stream.of(editor.getContentPane().getParent().getComponents())
                .filter(c -> c instanceof JMenuBar)
                .map(c -> getMenuByName((JMenuBar) c, I18n.tr("File")))
                .filter(Objects::nonNull)
                .flatMap(jm -> Stream.of(jm.getMenuComponents()))
                .filter(c -> c instanceof JMenu && I18n.tr("Examples").equals(((JMenu) c).getText()))
                .map(jm -> (JMenu) jm)
                .findFirst();
    }

    public JMenuItem walkTowardsMenuItem(
            List<JMenu> menus, List<String> parents, BiPredicate<String, JMenu> parentTitleMatcher, Predicate<JMenuItem> goalMatcher) {

        if (!parents.isEmpty()) {
            JMenu menuContainer = null;

            for (String parent : parents) {
                menuContainer = menus.stream()
                        .filter(menu -> parentTitleMatcher.test(parent, menu))
                        .findFirst()
                        .orElse(null);
                if (menuContainer == null) {
                    return null;
                }
            }

            if (menuContainer == null) {
                return null;
            }

            return Stream.of(menuContainer.getMenuComponents())
                    .filter(c -> c instanceof JMenuItem && goalMatcher.test((JMenuItem) c))
                    .map(c -> (JMenuItem) c)
                    .findFirst()
                    .orElse(null);
        }

        return menus.stream()
                .filter(m -> m instanceof JMenuItem && goalMatcher.test(m))
                .map(m -> (JMenuItem) m)
                .findFirst()
                .orElse(null);
    }

}
