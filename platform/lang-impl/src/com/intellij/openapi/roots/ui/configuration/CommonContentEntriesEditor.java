/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.openapi.roots.ui.configuration;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.ex.FileChooserKeys;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectBundle;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootModel;
import com.intellij.openapi.roots.ui.componentsList.components.ScrollablePanel;
import com.intellij.openapi.roots.ui.componentsList.layout.VerticalStackLayout;
import com.intellij.openapi.roots.ui.configuration.actions.IconWithTextAction;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.ex.VirtualFileManagerAdapter;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.border.CustomLineBorder;
import com.intellij.ui.roots.ToolbarPanel;
import com.intellij.util.Consumer;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.module.JpsModuleSourceRootType;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Eugene Zhuravlev
 *         Date: Oct 4, 2003
 *         Time: 6:54:57 PM
 */
public class CommonContentEntriesEditor extends ModuleElementsEditor {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.roots.ui.configuration.ContentEntriesEditor");
  public static final String NAME = ProjectBundle.message("module.paths.title");
  private static final Color BACKGROUND_COLOR = UIUtil.getListBackground();

  protected ContentEntryTreeEditor myRootTreeEditor;
  private MyContentEntryEditorListener myContentEntryEditorListener;
  protected JPanel myEditorsPanel;
  protected final Map<String, ContentEntryEditor> myEntryToEditorMap = new HashMap<String, ContentEntryEditor>();
  private String mySelectedEntryUrl;

  private VirtualFile myLastSelectedDir = null;
  private final String myModuleName;
  private final ModulesProvider myModulesProvider;
  private final ModuleConfigurationState myState;
  private final List<ModuleSourceRootEditHandler<?>> myEditHandlers = new ArrayList<ModuleSourceRootEditHandler<?>>();

  public CommonContentEntriesEditor(String moduleName, final ModuleConfigurationState state, JpsModuleSourceRootType<?>... rootTypes) {
    super(state);
    myState = state;
    myModuleName = moduleName;
    myModulesProvider = state.getModulesProvider();
    for (JpsModuleSourceRootType<?> type : rootTypes) {
      ContainerUtil.addIfNotNull(myEditHandlers, ModuleSourceRootEditHandler.getEditHandler(type));
    }
    final VirtualFileManagerAdapter fileManagerListener = new VirtualFileManagerAdapter() {
      @Override
      public void afterRefreshFinish(boolean asynchronous) {
        if (state.getProject().isDisposed()) {
          return;
        }
        final Module module = getModule();
        if (module == null || module.isDisposed()) return;
        for (final ContentEntryEditor editor : myEntryToEditorMap.values()) {
          editor.update();
        }
      }
    };
    final VirtualFileManager fileManager = VirtualFileManager.getInstance();
    fileManager.addVirtualFileManagerListener(fileManagerListener);
    registerDisposable(new Disposable() {
      @Override
      public void dispose() {
        fileManager.removeVirtualFileManagerListener(fileManagerListener);
      }
    });
  }

  @Override
  protected ModifiableRootModel getModel() {
    return myState.getRootModel();
  }

  @Override
  public String getHelpTopic() {
    return "projectStructure.modules.sources";
  }

  @Override
  public String getDisplayName() {
    return NAME;
  }

  protected final List<ModuleSourceRootEditHandler<?>> getEditHandlers() {
    return myEditHandlers;
  }

  @Override
  public void disposeUIResources() {
    if (myRootTreeEditor != null) {
      myRootTreeEditor.setContentEntryEditor(null);
    }

    myEntryToEditorMap.clear();
    super.disposeUIResources();
  }

  @Override
  public JPanel createComponentImpl() {
    final Module module = getModule();
    final Project project = module.getProject();

    myContentEntryEditorListener = new MyContentEntryEditorListener();

    final JPanel mainPanel = new JPanel(new BorderLayout());
    if (!Registry.is("ide.new.project.settings")) {
      mainPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
    }

    addAdditionalSettingsToPanel(mainPanel);

    final JPanel entriesPanel = new JPanel(new BorderLayout());

    final DefaultActionGroup group = new DefaultActionGroup();
    final AddContentEntryAction action = new AddContentEntryAction();
    action.registerCustomShortcutSet(KeyEvent.VK_C, InputEvent.ALT_DOWN_MASK, mainPanel);
    group.add(action);

    myEditorsPanel = new ScrollablePanel(new VerticalStackLayout());
    myEditorsPanel.setBackground(BACKGROUND_COLOR);
    JScrollPane myScrollPane = ScrollPaneFactory.createScrollPane(myEditorsPanel, Registry.is("ide.new.project.settings"));
    final ToolbarPanel toolbarPanel = new ToolbarPanel(myScrollPane, group);
    if (Registry.is("ide.new.project.settings")) {
      toolbarPanel.setBorder(new CustomLineBorder(1,0,0,0));
    }
    entriesPanel.add(toolbarPanel, BorderLayout.CENTER);

    final JBSplitter splitter = Registry.is("ide.new.project.settings") ? new OnePixelSplitter(false) : new JBSplitter(false);
    splitter.setProportion(0.6f);
    splitter.setHonorComponentsMinimumSize(true);

    myRootTreeEditor = createContentEntryTreeEditor(project);
    final JComponent component = myRootTreeEditor.createComponent();
    if (Registry.is("ide.new.project.settings")) {
      component.setBorder(new CustomLineBorder(1,0,0,0));
    }

    splitter.setFirstComponent(component);
    splitter.setSecondComponent(entriesPanel);
    JPanel contentPanel = new JPanel(new GridBagLayout());
    if (!Registry.is("ide.new.project.settings")) {
      contentPanel.setBorder(BorderFactory.createEtchedBorder());
    }
    final ActionToolbar actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.UNKNOWN, myRootTreeEditor.getEditingActionsGroup(), true);
    contentPanel.add(new JLabel("Mark as:"),
                     new GridBagConstraints(0, 0, 1, 1, 0, 0, GridBagConstraints.WEST, 0, new Insets(0, 10, 0, 10), 0, 0));
    contentPanel.add(actionToolbar.getComponent(),
                     new GridBagConstraints(1, 0, 1, 1, 1.0, 0.0, GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL,
                                            new Insets(0, 0, 0, 0), 0, 0));
    contentPanel.add(splitter,
                     new GridBagConstraints(0, GridBagConstraints.RELATIVE, 2, 1, 1.0, 1.0, GridBagConstraints.WEST, GridBagConstraints.BOTH,
                                            new Insets(0, 0, 0, 0), 0, 0));

    mainPanel.add(contentPanel, BorderLayout.CENTER);


    final JPanel innerPanel = createBottomControl(module);
    if (innerPanel != null) {
      mainPanel.add(innerPanel, BorderLayout.SOUTH);
    }

    final ModifiableRootModel model = getModel();
    if (model != null) {
      final ContentEntry[] contentEntries = model.getContentEntries();
      if (contentEntries.length > 0) {
        for (final ContentEntry contentEntry : contentEntries) {
          addContentEntryPanel(contentEntry.getUrl());
        }
        selectContentEntry(contentEntries[0].getUrl());
      }
    }

    return mainPanel;
  }

  @Nullable
  protected JPanel createBottomControl(Module module) {
    return null;
  }

  protected ContentEntryTreeEditor createContentEntryTreeEditor(Project project) {
    return new ContentEntryTreeEditor(project, myEditHandlers);
  }

  protected void addAdditionalSettingsToPanel(final JPanel mainPanel) {
  }

  protected Module getModule() {
    return myModulesProvider.getModule(myModuleName);
  }

  protected void addContentEntryPanel(final String contentEntry) {
    final ContentEntryEditor contentEntryEditor = createContentEntryEditor(contentEntry);
    contentEntryEditor.initUI();
    contentEntryEditor.addContentEntryEditorListener(myContentEntryEditorListener);
    registerDisposable(new Disposable() {
      @Override
      public void dispose() {
        contentEntryEditor.removeContentEntryEditorListener(myContentEntryEditorListener);
      }
    });
    myEntryToEditorMap.put(contentEntry, contentEntryEditor);
    Border border = BorderFactory.createEmptyBorder(2, 2, 0, 2);
    final JComponent component = contentEntryEditor.getComponent();
    final Border componentBorder = component.getBorder();
    if (componentBorder != null) {
      border = BorderFactory.createCompoundBorder(border, componentBorder);
    }
    if (Registry.is("ide.new.project.settings")) {
      component.setBorder(new EmptyBorder(0,0,0,0));
    } else {
      component.setBorder(border);
    }
    myEditorsPanel.add(component);
  }

  protected ContentEntryEditor createContentEntryEditor(String contentEntryUrl) {
    return new ContentEntryEditor(contentEntryUrl, myEditHandlers) {
      @Override
      protected ModifiableRootModel getModel() {
        return CommonContentEntriesEditor.this.getModel();
      }
    };
  }

  void selectContentEntry(final String contentEntryUrl) {
    if (mySelectedEntryUrl != null && mySelectedEntryUrl.equals(contentEntryUrl)) {
      return;
    }
    try {
      if (mySelectedEntryUrl != null) {
        ContentEntryEditor editor = myEntryToEditorMap.get(mySelectedEntryUrl);
        if (editor != null) {
          editor.setSelected(false);
        }
      }

      if (contentEntryUrl != null) {
        ContentEntryEditor editor = myEntryToEditorMap.get(contentEntryUrl);
        if (editor != null) {
          editor.setSelected(true);
          final JComponent component = editor.getComponent();
          final JComponent scroller = (JComponent)component.getParent();
          SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
              scroller.scrollRectToVisible(component.getBounds());
            }
          });
          myRootTreeEditor.setContentEntryEditor(editor);
          myRootTreeEditor.requestFocus();
        }
      }
    }
    finally {
      mySelectedEntryUrl = contentEntryUrl;
    }
  }

  @Override
  public void moduleStateChanged() {
    if (myRootTreeEditor != null) { //in order to update exclude output root if it is under content root
      myRootTreeEditor.update();
    }
  }

  @Nullable
  private String getNextContentEntry(final String contentEntryUrl) {
    return getAdjacentContentEntry(contentEntryUrl, 1);
  }

  @Nullable
  private String getAdjacentContentEntry(final String contentEntryUrl, int delta) {
    final ContentEntry[] contentEntries = getModel().getContentEntries();
    for (int idx = 0; idx < contentEntries.length; idx++) {
      ContentEntry entry = contentEntries[idx];
      if (contentEntryUrl.equals(entry.getUrl())) {
        int nextEntryIndex = (idx + delta) % contentEntries.length;
        if (nextEntryIndex < 0) {
          nextEntryIndex += contentEntries.length;
        }
        return nextEntryIndex == idx ? null : contentEntries[nextEntryIndex].getUrl();
      }
    }
    return null;
  }

  protected List<ContentEntry> addContentEntries(final VirtualFile[] files) {
    List<ContentEntry> contentEntries = new ArrayList<ContentEntry>();
    for (final VirtualFile file : files) {
      if (isAlreadyAdded(file)) {
        continue;
      }
      final ContentEntry contentEntry = getModel().addContentEntry(file);
      contentEntries.add(contentEntry);
    }
    return contentEntries;
  }

  private boolean isAlreadyAdded(VirtualFile file) {
    final VirtualFile[] contentRoots = getModel().getContentRoots();
    for (VirtualFile contentRoot : contentRoots) {
      if (contentRoot.equals(file)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void saveData() {
  }

  protected void addContentEntryPanels(ContentEntry[] contentEntriesArray) {
    for (ContentEntry contentEntry : contentEntriesArray) {
      addContentEntryPanel(contentEntry.getUrl());
    }
    myEditorsPanel.revalidate();
    myEditorsPanel.repaint();
    selectContentEntry(contentEntriesArray[contentEntriesArray.length - 1].getUrl());
  }

  private final class MyContentEntryEditorListener extends ContentEntryEditorListenerAdapter {
    @Override
    public void editingStarted(@NotNull ContentEntryEditor editor) {
      selectContentEntry(editor.getContentEntryUrl());
    }

    @Override
    public void beforeEntryDeleted(@NotNull ContentEntryEditor editor) {
      final String entryUrl = editor.getContentEntryUrl();
      if (mySelectedEntryUrl != null && mySelectedEntryUrl.equals(entryUrl)) {
        myRootTreeEditor.setContentEntryEditor(null);
      }
      final String nextContentEntryUrl = getNextContentEntry(entryUrl);
      removeContentEntryPanel(entryUrl);
      selectContentEntry(nextContentEntryUrl);
      editor.removeContentEntryEditorListener(this);
    }

    @Override
    public void navigationRequested(@NotNull ContentEntryEditor editor, VirtualFile file) {
      if (mySelectedEntryUrl != null && mySelectedEntryUrl.equals(editor.getContentEntryUrl())) {
        myRootTreeEditor.requestFocus();
        myRootTreeEditor.select(file);
      }
      else {
        selectContentEntry(editor.getContentEntryUrl());
        myRootTreeEditor.requestFocus();
        myRootTreeEditor.select(file);
      }
    }

    private void removeContentEntryPanel(final String contentEntryUrl) {
      ContentEntryEditor editor = myEntryToEditorMap.get(contentEntryUrl);
      if (editor != null) {
        myEditorsPanel.remove(editor.getComponent());
        myEntryToEditorMap.remove(contentEntryUrl);
        myEditorsPanel.revalidate();
        myEditorsPanel.repaint();
      }
    }
  }

  private class AddContentEntryAction extends IconWithTextAction implements DumbAware {
    private final FileChooserDescriptor myDescriptor;

    public AddContentEntryAction() {
      super(ProjectBundle.message("module.paths.add.content.action"),
            ProjectBundle.message("module.paths.add.content.action.description"), AllIcons.Modules.AddContentEntry);
      myDescriptor = new FileChooserDescriptor(false, true, true, false, true, true) {
        @Override
        public void validateSelectedFiles(VirtualFile[] files) throws Exception {
          validateContentEntriesCandidates(files);
        }
      };
      myDescriptor.putUserData(LangDataKeys.MODULE_CONTEXT, getModule());
      myDescriptor.setTitle(ProjectBundle.message("module.paths.add.content.title"));
      myDescriptor.setDescription(ProjectBundle.message("module.paths.add.content.prompt"));
      myDescriptor.putUserData(FileChooserKeys.DELETE_ACTION_AVAILABLE, false);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      FileChooser.chooseFiles(myDescriptor, myProject, myLastSelectedDir, new Consumer<List<VirtualFile>>() {
        @Override
        public void consume(List<VirtualFile> files) {
          myLastSelectedDir = files.get(0);
          addContentEntries(VfsUtilCore.toVirtualFileArray(files));
        }
      });
    }

    @Nullable
    private ContentEntry getContentEntry(final String url) {
      final ContentEntry[] entries = getModel().getContentEntries();
      for (final ContentEntry entry : entries) {
        if (entry.getUrl().equals(url)) return entry;
      }

      return null;
    }

    private void validateContentEntriesCandidates(VirtualFile[] files) throws Exception {
      for (final VirtualFile file : files) {
        // check for collisions with already existing entries
        for (final String contentEntryUrl : myEntryToEditorMap.keySet()) {
          final ContentEntry contentEntry = getContentEntry(contentEntryUrl);
          if (contentEntry == null) continue;
          final VirtualFile contentEntryFile = contentEntry.getFile();
          if (contentEntryFile == null) {
            continue;  // skip invalid entry
          }
          if (contentEntryFile.equals(file)) {
            throw new Exception(ProjectBundle.message("module.paths.add.content.already.exists.error", file.getPresentableUrl()));
          }
          if (VfsUtilCore.isAncestor(contentEntryFile, file, true)) {
            // intersection not allowed
            throw new Exception(
              ProjectBundle.message("module.paths.add.content.intersect.error", file.getPresentableUrl(),
                                    contentEntryFile.getPresentableUrl()));
          }
          if (VfsUtilCore.isAncestor(file, contentEntryFile, true)) {
            // intersection not allowed
            throw new Exception(
              ProjectBundle.message("module.paths.add.content.dominate.error", file.getPresentableUrl(),
                                    contentEntryFile.getPresentableUrl()));
          }
        }
        // check if the same root is configured for another module
        final Module[] modules = myModulesProvider.getModules();
        for (final Module module : modules) {
          if (myModuleName.equals(module.getName())) {
            continue;
          }
          ModuleRootModel rootModel = myModulesProvider.getRootModel(module);
          LOG.assertTrue(rootModel != null);
          final VirtualFile[] moduleContentRoots = rootModel.getContentRoots();
          for (VirtualFile moduleContentRoot : moduleContentRoots) {
            if (file.equals(moduleContentRoot)) {
              throw new Exception(
                ProjectBundle.message("module.paths.add.content.duplicate.error", file.getPresentableUrl(), module.getName()));
            }
          }
        }
      }
    }

  }

}
