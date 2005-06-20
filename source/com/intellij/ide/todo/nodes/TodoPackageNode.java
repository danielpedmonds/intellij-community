package com.intellij.ide.todo.nodes;

import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ViewSettings;
import com.intellij.ide.projectView.impl.nodes.PackageElement;
import com.intellij.ide.projectView.impl.nodes.PackageElementNode;
import com.intellij.ide.todo.HighlightedRegionProvider;
import com.intellij.ide.todo.TodoFileDirAndModuleComparator;
import com.intellij.ide.todo.TodoTreeBuilder;
import com.intellij.ide.todo.TodoTreeStructure;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.HighlightedRegion;
import com.intellij.usageView.UsageTreeColors;
import com.intellij.usageView.UsageTreeColorsScheme;
import com.intellij.util.ArrayUtil;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

public final class TodoPackageNode extends PackageElementNode implements HighlightedRegionProvider {
  private final ArrayList myHighlightedRegions;
  private final TodoTreeBuilder myBuilder;


  public TodoPackageNode(Project project,
                         PackageElement element,
                         TodoTreeBuilder builder) {
    super(project, element, ViewSettings.DEFAULT);
    myBuilder = builder;
    myHighlightedRegions = new ArrayList(2);
  }

  public ArrayList getHighlightedRegions() {
    return myHighlightedRegions;
  }

  public void update(PresentationData data) {
    super.update(data);
    int fileCount = getStructure().getFileCount(getValue());
    if (getValue() == null || !getValue().getPackage().isValid() || fileCount == 0) {
      setValue(null);
      return;
    }

    PsiPackage aPackage = getValue().getPackage();
    String newName;
    if (getStructure().areFlattenPackages()) {
      newName = aPackage.getQualifiedName();
    }
    else {
      newName = aPackage.getName() != null ? aPackage.getName() : "";
    }


    StringBuffer sb = new StringBuffer(newName);
    int nameEndOffset = newName.length();
    int todoItemCount = getStructure().getTodoItemCount(getValue());
    sb.append(" ( ").append(todoItemCount).append(" item");
    if (todoItemCount > 1) {
      sb.append('s');
    }
    sb.append(" in " + fileCount).append(" file");
    if (fileCount > 1) {
      sb.append('s');
    }
    sb.append(" )");
    newName = sb.toString();

    myHighlightedRegions.clear();

    TextAttributes textAttributes = new TextAttributes();
    Color newColor = null;

    if (CopyPasteManager.getInstance().isCutElement(getValue())) {
      newColor = CopyPasteManager.CUT_COLOR;
    }
    textAttributes.setForegroundColor(newColor);
    myHighlightedRegions.add(new HighlightedRegion(0, nameEndOffset, textAttributes));

    EditorColorsScheme colorsScheme = UsageTreeColorsScheme.getInstance().getScheme();
    myHighlightedRegions.add(
      new HighlightedRegion(nameEndOffset, newName.length(), colorsScheme.getAttributes(UsageTreeColors.NUMBER_OF_USAGES)));

    data.setPresentableText(newName);
  }

  private TodoTreeStructure getStructure() {
    return myBuilder.getTodoTreeStructure();
  }

  public Collection<AbstractTreeNode> getChildren() {
    ArrayList<AbstractTreeNode> children = new ArrayList<AbstractTreeNode>();
    final ProjectFileIndex projectFileIndex = ProjectRootManager.getInstance(getProject()).getFileIndex();
    final PsiPackage psiPackage = getValue().getPackage();
    final Module module = getValue().getModule();
    if (!getStructure().getIsFlattenPackages() || psiPackage == null) {
      final Iterator<PsiFile> iterator = myBuilder.getFiles(getValue());
      while (iterator.hasNext()) {
        final PsiFile psiFile = iterator.next();
        final Module psiFileModule = projectFileIndex.getModuleForFile(psiFile.getVirtualFile());
        //group by module
        if (module != null && psiFileModule != null && !module.equals(psiFileModule)){
          continue;
        }
        // Add files
        final PsiDirectory containingDirectory = psiFile.getContainingDirectory();
        TodoFileNode todoFileNode = new TodoFileNode(getProject(), psiFile, myBuilder, false);
        if (ArrayUtil.find(psiPackage.getDirectories(), containingDirectory) > -1 && !children.contains(todoFileNode)) {
          children.add(todoFileNode);
          continue;
        }
        // Add packages
        PsiDirectory _dir = psiFile.getContainingDirectory();
        while (_dir != null) {
          final PsiDirectory parentDirectory = _dir.getParentDirectory();
          if (parentDirectory != null){
            PsiPackage _package = _dir.getPackage();
            TodoPackageNode todoPackageNode = new TodoPackageNode(getProject(), new PackageElement(module, _package, false), myBuilder);
            if (_package != null && _package.getParentPackage() != null && psiPackage.equals(_package.getParentPackage()) && !children.contains(todoPackageNode)) {
              children.add(todoPackageNode);
              break;
            }
          }
          _dir = parentDirectory;
        }
      }
      Collections.sort(children, TodoFileDirAndModuleComparator.ourInstance);
    }
    else { // flatten packages
      final Iterator<PsiFile> iterator = myBuilder.getFiles(getValue());
      while (iterator.hasNext()) {
        final PsiFile psiFile = iterator.next();
         //group by module
        final Module psiFileModule = projectFileIndex.getModuleForFile(psiFile.getVirtualFile());
        if (module != null && psiFileModule != null && !module.equals(psiFileModule)){
          continue;
        }
        final PsiDirectory _dir = psiFile.getContainingDirectory();
        // Add files
        TodoFileNode todoFileNode = new TodoFileNode(getProject(), psiFile, myBuilder, false);
        if (ArrayUtil.find(psiPackage.getDirectories(), _dir) > -1 && !children.contains(todoFileNode)) {
          children.add(todoFileNode);
          continue;
        }
        // Add directories
         PsiPackage _package = _dir.getPackage();
        final PackageElement element = new PackageElement(module, _package, false);
        if (_package != null && !TodoPackageUtil.isPackageEmpty(element, myBuilder, getProject())){
           TodoPackageNode todoPackageNode = new TodoPackageNode(getProject(), element, myBuilder);
           if (PsiTreeUtil.isAncestor(psiPackage, _package, true) && !children.contains(todoPackageNode)) {
             children.add(todoPackageNode);
             continue;
           }
         }

      }
      Collections.sort(children, TodoFileDirAndModuleComparator.ourInstance);
    }
    return children;
  }
}

