/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.execution.application;

import com.intellij.execution.lineMarker.ExecutorAction;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiMethodUtil;
import org.jetbrains.annotations.Nullable;

/**
 * @author Dmitry Avdeev
 */
public class ApplicationRunLineMarkerProvider extends RunLineMarkerContributor {

  @Nullable
  @Override
  public Info getInfo(PsiElement e) {
    if (isIdentifier(e)) {
      PsiElement element = e.getParent();
      if (element instanceof PsiClass && PsiMethodUtil.findMainInClass((PsiClass)element) != null)
        return new Info(ApplicationConfigurationType.getInstance().getIcon(), null, ExecutorAction.getActions(0));
      if (element instanceof PsiMethod && "main".equals(((PsiMethod)element).getName()) && PsiMethodUtil.isMainMethod((PsiMethod)element))
        return new Info(ApplicationConfigurationType.getInstance().getIcon(), null, ExecutorAction.getActions(0));
    }
    return null;
  }

  protected boolean isIdentifier(PsiElement e) {
    return e instanceof PsiIdentifier;
  }
}
