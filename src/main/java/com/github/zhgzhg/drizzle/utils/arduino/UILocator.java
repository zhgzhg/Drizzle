package com.github.zhgzhg.drizzle.utils.arduino;

import com.github.zhgzhg.drizzle.Drizzle;
import processing.app.Base;
import processing.app.Editor;
import processing.app.EditorConsole;
import processing.app.I18n;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
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

    public Optional<JMenuItem> drizzleMenu() {
        return Stream.of(editor.getContentPane().getParent().getComponents())
                .filter(c -> c instanceof JMenuBar)
                .flatMap(jb -> Stream.of(((JMenuBar) jb).getMenu(3).getMenuComponents()))
                .filter(c -> c instanceof JMenuItem && Drizzle.MENU_TITLE.equals(((JMenuItem)c).getText()))
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
        return Base.INSTANCE.getBoardsCustomMenus();
    }

    public Optional<JMenu> sketchIncludeLibraryMenu() {
        return Stream.of(editor.getContentPane().getParent().getComponents())
                .filter(c -> c instanceof JMenuBar)
                .map(jb -> ((JMenuBar) jb).getMenu(2).getMenuComponent(6)) // a.k.a "Sketch/Include Library"
                .filter(jm -> jm instanceof JMenu)
                .map(jm -> (JMenu) jm)
                .findFirst();
    }

    public Optional<JMenu> filesExamplesMenu() {
        return Stream.of(editor.getContentPane().getParent().getComponents())
                .filter(c -> c instanceof JMenuBar)
                .map(jb -> ((JMenuBar) jb).getMenu(0).getMenuComponent(4)) // a.k.a "File/Examples"
                .map(jm -> (JMenu) jm)
                .findFirst();
    }

    public JMenuItem walkTowardsMenuItem(
            List<JMenu> menus, List<String> parents, BiPredicate<String, String> parentTitleMatcher, Predicate<String> goalMatcher) {

        if (!parents.isEmpty()) {
            JMenu menuContainer = null;

            for (String parent : parents) {
                menuContainer = menus.stream()
                        .filter(menu -> parentTitleMatcher.test(parent, menu.getText()))
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
                    .filter(c -> c instanceof JMenuItem && goalMatcher.test(((JMenuItem) c).getText()))
                    .map(c -> (JMenuItem) c)
                    .findFirst()
                    .orElse(null);
        }

        return menus.stream()
                .filter(m -> m instanceof JMenuItem && goalMatcher.test(m.getText()))
                .map(m -> (JMenuItem) m)
                .findFirst()
                .orElse(null);
    }

}