/*
 * Copyright 2003-2015 Dave Griffith, Bas Leijdekkers
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
package com.siyeh.ig.style;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.ig.PsiReplacementUtil;
import com.siyeh.ig.psiutils.ParenthesesUtils;
import com.siyeh.ig.psiutils.TypeUtils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class StringBufferReplaceableByStringInspection extends BaseInspection {

  @Override
  @NotNull
  public String getDisplayName() {
    return InspectionGadgetsBundle.message("string.buffer.replaceable.by.string.display.name");
  }

  @Override
  @NotNull
  public String buildErrorString(Object... infos) {
    final PsiElement element = (PsiElement)infos[0];
    if (element instanceof PsiNewExpression) {
      return InspectionGadgetsBundle.message("new.string.buffer.replaceable.by.string.problem.descriptor");
    }
    final String typeText = ((PsiType)infos[1]).getPresentableText();
    return InspectionGadgetsBundle.message("string.buffer.replaceable.by.string.problem.descriptor", typeText);
  }

  @Override
  protected InspectionGadgetsFix buildFix(Object... infos) {
    final String typeText = ((PsiType)infos[1]).getCanonicalText();
    return new StringBufferReplaceableByStringFix(CommonClassNames.JAVA_LANG_STRING_BUILDER.equals(typeText));
  }

  private static class StringBufferReplaceableByStringFix extends InspectionGadgetsFix {

    private final boolean isStringBuilder;

    private StringBufferReplaceableByStringFix(boolean isStringBuilder) {
      this.isStringBuilder = isStringBuilder;
    }

    @NotNull
    @Override
    public String getName() {
      if (isStringBuilder) {
        return InspectionGadgetsBundle.message("string.builder.replaceable.by.string.quickfix");
      }
      else {
        return InspectionGadgetsBundle.message("string.buffer.replaceable.by.string.quickfix");
      }
    }

    @NotNull
    @Override
    public String getFamilyName() {
      return "Replace with 'String'";
    }

    @Override
    protected void doFix(Project project, ProblemDescriptor descriptor) {
      final PsiElement element = descriptor.getPsiElement();
      final PsiElement parent = element.getParent();
      if (!(parent instanceof PsiVariable)) {
        if (parent instanceof PsiNewExpression) {
          final PsiExpression stringBuilderExpression = getCompleteExpression(parent);
          final StringBuilder stringExpression = buildStringExpression(stringBuilderExpression, new StringBuilder());
          if (stringExpression != null && stringBuilderExpression != null) {
            PsiReplacementUtil.replaceExpression(stringBuilderExpression, stringExpression.toString());
          }
        }
        return;
      }
      final PsiVariable variable = (PsiVariable)parent;
      final PsiTypeElement originalTypeElement = variable.getTypeElement();
      if (originalTypeElement == null) {
        return;
      }
      final PsiExpression initializer = variable.getInitializer();
      if (initializer == null) {
        return;
      }
      final StringBuilder builder;
      if (isAppendCall(initializer)) {
        builder = buildStringExpression(initializer, new StringBuilder());
        if (builder == null) {
          return;
        }
      } else if (initializer instanceof PsiNewExpression) {
        final PsiNewExpression newExpression = (PsiNewExpression)initializer;
        final PsiExpressionList argumentList = newExpression.getArgumentList();
        if (argumentList == null) {
          return;
        }
        final PsiExpression[] arguments = argumentList.getExpressions();
        if (arguments.length == 0 || PsiType.INT.equals(arguments[0].getType())) {
          builder = new StringBuilder();
        } else {
          builder = new StringBuilder(arguments[0].getText());
        }
      } else {
        return;
      }
      final PsiCodeBlock codeBlock = PsiTreeUtil.getParentOfType(variable, PsiCodeBlock.class);
      if (codeBlock == null) {
        return;
      }
      final StringBuildingVisitor visitor = new StringBuildingVisitor(variable, builder);
      codeBlock.accept(visitor);
      if (visitor.hadProblem()) {
        return;
      }
      final List<PsiMethodCallExpression> expressions = visitor.getExpressions();
      final String expression = builder.toString().trim();
      final PsiMethodCallExpression lastExpression = expressions.get(expressions.size() - 1);
      final boolean useVariable = expression.contains("\n") && !isVariableInitializer(lastExpression);
      if (useVariable) {
        final PsiStatement statement = PsiTreeUtil.getParentOfType(variable, PsiStatement.class);
        if (statement == null) {
          return;
        }
        final PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        final PsiStatement newStatement =
          factory.createStatementFromText(CommonClassNames.JAVA_LANG_STRING + ' ' + variable.getName() + '=' + expression + ';', variable);
        codeBlock.addBefore(newStatement, statement);
      }
      variable.delete();
      for (int i = 0, size = expressions.size() - 1; i < size; i++) {
        expressions.get(i).getParent().delete();
      }
      if (useVariable) {
        PsiReplacementUtil.replaceExpression(lastExpression, variable.getName());
      }
      else {
        PsiReplacementUtil.replaceExpression(lastExpression, expression);
      }
    }

    private static boolean isVariableInitializer(PsiExpression expression) {
      final PsiElement parent = expression.getParent();
      if (!(parent instanceof PsiVariable)) {
        return false;
      }
      final PsiVariable variable = (PsiVariable)parent;
      final PsiExpression initializer = variable.getInitializer();
      return initializer == expression;
    }

    @Nullable
    private static StringBuilder buildStringExpression(PsiElement element, @NonNls StringBuilder result) {
      if (element instanceof PsiNewExpression) {
        final PsiNewExpression newExpression = (PsiNewExpression)element;
        final PsiExpressionList argumentList = newExpression.getArgumentList();
        if (argumentList == null) {
          return null;
        }
        final PsiExpression[] arguments = argumentList.getExpressions();
        if (arguments.length == 1) {
          final PsiExpression argument = arguments[0];
          final PsiType type = argument.getType();
          if (!PsiType.INT.equals(type)) {
            if (type != null && type.equalsToText("java.lang.CharSequence")) {
              result.append("String.valueOf(").append(argument.getText()).append(')');
            }
            else if (ParenthesesUtils.getPrecedence(argument) > ParenthesesUtils.ADDITIVE_PRECEDENCE) {
              result.append('(').append(argument.getText()).append(')');
            }
            else {
              result.append(argument.getText());
            }
          }
        }
        return result;
      }
      for (PsiElement child : element.getChildren()) {
        if (child instanceof PsiExpressionList) {
          continue;
        }
        if (buildStringExpression(child, result) == null) {
          return null;
        }
      }

      if (element instanceof PsiWhiteSpace) {
        if (element.getText().contains("\n") && result.length() > 0) {
          result.append('\n'); // keep line break structure
        }
      }
      else if (element instanceof PsiComment) {
        result.append(element.getText()); // keep comments
      }
      else if (element instanceof PsiMethodCallExpression) {
        final PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)element;
        final PsiExpressionList argumentList = methodCallExpression.getArgumentList();

        final PsiReferenceExpression methodExpression = methodCallExpression.getMethodExpression();
        final String referenceName = methodExpression.getReferenceName();
        if ("toString".equals(referenceName)) {
          if (result.length() == 0) {
            result.append("\"\"");
          }
        }
        else if ("append".equals(referenceName)){
          final PsiExpression[] arguments = argumentList.getExpressions();
          if (arguments.length == 0) {
            return null;
          }
          if (arguments.length > 1) {
            if (result.length() != 0) {
              insertPlus(result);
            }
            result.append("String.valueOf").append(argumentList.getText());
            return result;
          }
          final PsiExpression argument = arguments[0];
          final PsiType type = argument.getType();
          final String argumentText = argument.getText();
          if (result.length() != 0) {
            insertPlus(result);
            if (ParenthesesUtils.getPrecedence(argument) > ParenthesesUtils.ADDITIVE_PRECEDENCE ||
                (type instanceof PsiPrimitiveType && ParenthesesUtils.getPrecedence(argument) == ParenthesesUtils.ADDITIVE_PRECEDENCE)) {
              result.append('(').append(argumentText).append(')');
            }
            else {
              if (StringUtil.startsWithChar(argumentText, '+')) {
                result.append(' ');
              }
              result.append(argumentText);
            }
          }
          else {
            if (type instanceof PsiPrimitiveType) {
              if (argument instanceof PsiLiteralExpression) {
                final PsiLiteralExpression literalExpression = (PsiLiteralExpression)argument;
                if (PsiType.CHAR.equals(literalExpression.getType())) {
                  result.append('"');
                  final Character c = (Character)literalExpression.getValue();
                  if (c != null) {
                    result.append(StringUtil.escapeStringCharacters(c.toString()));
                  }
                  result.append('"');
                }
                else {
                  result.append('"').append(literalExpression.getValue()).append('"');
                }
              }
              else {
                result.append("String.valueOf(").append(argumentText).append(")");
              }
            }
            else {
              if (ParenthesesUtils.getPrecedence(argument) >= ParenthesesUtils.ADDITIVE_PRECEDENCE) {
                result.append('(').append(argumentText).append(')');
              }
              else {
                if (type != null && !type.equalsToText(CommonClassNames.JAVA_LANG_STRING)) {
                  result.append("String.valueOf(").append(argumentText).append(")");
                }
                else {
                  result.append(argumentText);
                }
              }
            }
          }
        }
      }
      return result;
    }

    private static void insertPlus(@NonNls StringBuilder result) {
      if (result.charAt(result.length() - 1) == '\n') {
        int index = result.length() - 2;
        while (index > 0) {
          final char c = result.charAt(index);
          if (c == '/' && result.charAt(index - 1) == '/') { // special handling of end-of-line comment
            result.insert(index - 1, "+ ");
            return;
          }
          if (c == '\n') {
            break;
          }
          index--;
        }
        result.insert(result.length() - 1, '+');
      }
      else {
        result.append('+');
      }
    }

    private static class StringBuildingVisitor extends JavaRecursiveElementWalkingVisitor {

      private final PsiVariable myVariable;
      private final StringBuilder myBuilder;
      private final List<PsiMethodCallExpression> expressions = ContainerUtil.newArrayList();
      private boolean myProblem;

      private StringBuildingVisitor(@NotNull PsiVariable variable, StringBuilder builder) {
        myVariable = variable;
        myBuilder = builder;
      }

      @Override
      public void visitReferenceExpression(PsiReferenceExpression expression) {
        if (myProblem) {
          return;
        }
        super.visitReferenceExpression(expression);
        if (expression.getQualifierExpression() != null) {
          return;
        }
        final PsiElement target = expression.resolve();
        if (!myVariable.equals(target)) {
          return;
        }
        PsiMethodCallExpression methodCallExpression = null;
        PsiElement parent = expression.getParent();
        PsiElement grandParent = parent.getParent();
        while (parent instanceof PsiReferenceExpression && grandParent instanceof PsiMethodCallExpression) {
          methodCallExpression = (PsiMethodCallExpression)grandParent;
          parent = methodCallExpression.getParent();
          grandParent = parent.getParent();
          if ("toString".equals(methodCallExpression.getMethodExpression().getReferenceName())) {
            break;
          }
        }
        if (buildStringExpression(methodCallExpression, myBuilder) == null) {
          myProblem = true;
        }
        myBuilder.append('\n');
        expressions.add(methodCallExpression);
      }

      public List<PsiMethodCallExpression> getExpressions() {
        return expressions;
      }

      private boolean hadProblem() {
        return myProblem;
      }
    }
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new StringBufferReplaceableByStringVisitor();
  }

  private static class StringBufferReplaceableByStringVisitor extends BaseInspectionVisitor {

    @Override
    public void visitLocalVariable(@NotNull PsiLocalVariable variable) {
      super.visitLocalVariable(variable);
      final PsiType type = variable.getType();
      if (!TypeUtils.typeEquals(CommonClassNames.JAVA_LANG_STRING_BUFFER, type) &&
          !TypeUtils.typeEquals(CommonClassNames.JAVA_LANG_STRING_BUILDER, type)) {
        return;
      }
      final PsiExpression initializer = variable.getInitializer();
      if (!isNewStringBufferOrStringBuilder(initializer)) {
        return;
      }
      final PsiCodeBlock codeBlock = PsiTreeUtil.getParentOfType(variable, PsiCodeBlock.class);
      if (codeBlock == null) {
        return;
      }
      final ReplaceableByStringVisitor visitor = new ReplaceableByStringVisitor(variable);
      codeBlock.accept(visitor);
      if (!visitor.isReplaceable()) {
        return;
      }
      registerVariableError(variable, variable, type);
    }

    @Override
    public void visitNewExpression(PsiNewExpression expression) {
      super.visitNewExpression(expression);
      final PsiType type = expression.getType();
      if (!TypeUtils.typeEquals(CommonClassNames.JAVA_LANG_STRING_BUFFER, type) &&
          !TypeUtils.typeEquals(CommonClassNames.JAVA_LANG_STRING_BUILDER, type)) {
        return;
      }
      final PsiExpression completeExpression = getCompleteExpression(expression);
      if (completeExpression == null) {
        return;
      }
      registerNewExpressionError(expression, expression, type);
    }

    private static boolean isNewStringBufferOrStringBuilder(PsiExpression expression) {
      if (expression == null) {
        return false;
      }
      if (expression instanceof PsiNewExpression) {
        return true;
      }
      if (!isAppendCall(expression) || !(expression instanceof PsiMethodCallExpression)) {
        return false;
      }
      final PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)expression;
      final PsiReferenceExpression methodExpression = methodCallExpression.getMethodExpression();
      final PsiExpression qualifier = methodExpression.getQualifierExpression();
      return isNewStringBufferOrStringBuilder(qualifier);
    }
  }

  private static boolean isAppendCall(PsiElement element) {
    if (!(element instanceof PsiMethodCallExpression)) {
      return false;
    }
    final PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)element;
    final PsiReferenceExpression methodExpression = methodCallExpression.getMethodExpression();
    @NonNls final String methodName = methodExpression.getReferenceName();
    if (!"append".equals(methodName)) {
      return false;
    }
    final PsiExpressionList argumentList = methodCallExpression.getArgumentList();
    final PsiExpression[] arguments = argumentList.getExpressions();
    if (arguments.length == 3) {
      return arguments[0].getType() instanceof PsiArrayType &&
             PsiType.INT.equals(arguments[1].getType()) && PsiType.INT.equals(arguments[2].getType());
    }
    return arguments.length == 1;
  }

  private static boolean isToStringCall(PsiElement element) {
    if (!(element instanceof PsiMethodCallExpression)) {
      return false;
    }
    final PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)element;
    final PsiReferenceExpression methodExpression = methodCallExpression.getMethodExpression();
    @NonNls final String methodName = methodExpression.getReferenceName();
    if (!"toString".equals(methodName)) {
      return false;
    }
    final PsiExpressionList argumentList = methodCallExpression.getArgumentList();
    final PsiExpression[] arguments = argumentList.getExpressions();
    return arguments.length == 0;
  }

  @Nullable
  private static PsiExpression getCompleteExpression(PsiElement element) {
    PsiElement completeExpression = element;
    boolean found = false;
    while (true) {
      final PsiElement parent = completeExpression.getParent();
      if (!(parent instanceof PsiReferenceExpression)) {
        break;
      }
      final PsiElement grandParent = parent.getParent();
      if (isToStringCall(grandParent)) {
        found = true;
      } else if (!isAppendCall(grandParent)) {
        return null;
      }
      completeExpression = grandParent;
      if (found) {
        return (PsiExpression)completeExpression;
      }
    }
    return null;
  }

  private static class ReplaceableByStringVisitor extends JavaRecursiveElementWalkingVisitor {

    private final PsiElement myParent;
    private final PsiVariable myVariable;
    private boolean myReplaceable = true;
    private boolean myPossibleSideEffect;
    private boolean myToStringFound;

    ReplaceableByStringVisitor(@NotNull PsiVariable variable) {
      myVariable = variable;
      myParent = PsiTreeUtil.getParentOfType(variable, PsiCodeBlock.class, PsiIfStatement.class, PsiLoopStatement.class);
    }

    public boolean isReplaceable() {
      return myReplaceable && myToStringFound;
    }

    @Override
    public void visitElement(PsiElement element) {
      if (!myReplaceable) {
        return;
      }
      super.visitElement(element);
    }

    @Override
    public void visitAssignmentExpression(PsiAssignmentExpression expression) {
      super.visitAssignmentExpression(expression);
      if (expression.getTextOffset() > myVariable.getTextOffset() && !myToStringFound) {
        myPossibleSideEffect = true;
      }
    }

    @Override
    public void visitPostfixExpression(PsiPostfixExpression expression) {
      super.visitPostfixExpression(expression);
      if (expression.getTextOffset() > myVariable.getTextOffset() && !myToStringFound) {
        myPossibleSideEffect = true;
      }
    }

    @Override
    public void visitPrefixExpression(PsiPrefixExpression expression) {
      super.visitPrefixExpression(expression);
      if (expression.getTextOffset() > myVariable.getTextOffset() && !myToStringFound) {
        myPossibleSideEffect = true;
      }
    }

    @Override
    public void visitMethodCallExpression(PsiMethodCallExpression expression) {
      super.visitMethodCallExpression(expression);
      if (expression.getTextOffset() < myVariable.getTextOffset() || myToStringFound) {
        return;
      }
      final PsiMethod method = expression.resolveMethod();
      if (method == null) {
        myPossibleSideEffect = true;
        return;
      }
      final PsiClass aClass = method.getContainingClass();
      if (aClass == null) {
        myPossibleSideEffect = true;
        return;
      }
      final String name = aClass.getQualifiedName();
      if (CommonClassNames.JAVA_LANG_STRING_BUFFER.equals(name) ||
        CommonClassNames.JAVA_LANG_STRING_BUILDER.equals(name)) {
        return;
      }
      if (isArgumentOfStringBuilderMethod(expression)) {
        return;
      }
      myPossibleSideEffect = true;
    }

    private boolean isArgumentOfStringBuilderMethod(PsiMethodCallExpression expression) {
      final PsiExpressionList parent = PsiTreeUtil.getParentOfType(expression, PsiExpressionList.class, true, PsiStatement.class);
      if (parent == null) {
        return false;
      }
      final PsiElement grandParent = parent.getParent();
      if (grandParent instanceof PsiMethodCallExpression) {
        final PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)grandParent;
        if (!isCallToStringBuilderMethod(methodCallExpression)) {
          return isArgumentOfStringBuilderMethod(methodCallExpression);
        }
        return true;
      }
      if (grandParent instanceof PsiNewExpression) {
        final PsiLocalVariable variable = PsiTreeUtil.getParentOfType(grandParent, PsiLocalVariable.class, true, PsiExpressionList.class);
        if (!myVariable.equals(variable)) {
          return false;
        }
        final PsiNewExpression newExpression = (PsiNewExpression)grandParent;
        final PsiMethod constructor = newExpression.resolveMethod();
        if (constructor == null) {
          return false;
        }
        final PsiClass aClass = constructor.getContainingClass();
        if (aClass == null) {
          return false;
        }
        final String name = aClass.getQualifiedName();
        return CommonClassNames.JAVA_LANG_STRING_BUFFER.equals(name) ||
               CommonClassNames.JAVA_LANG_STRING_BUILDER.equals(name);
      }
      return false;
    }

    private boolean isCallToStringBuilderMethod(PsiMethodCallExpression methodCallExpression) {
      final PsiReferenceExpression methodExpression = methodCallExpression.getMethodExpression();
      PsiExpression qualifier = methodExpression.getQualifierExpression();
      while (qualifier instanceof PsiMethodCallExpression) {
        final PsiMethodCallExpression callExpression = (PsiMethodCallExpression)qualifier;
        final PsiReferenceExpression methodExpression1 = callExpression.getMethodExpression();
        qualifier = methodExpression1.getQualifierExpression();
      }
      if (!(qualifier instanceof PsiReferenceExpression)) {
        return false;
      }
      final PsiReferenceExpression referenceExpression = (PsiReferenceExpression)qualifier;
      final PsiElement target = referenceExpression.resolve();
      if (!myVariable.equals(target)) {
        return false;
      }
      final PsiMethod method = methodCallExpression.resolveMethod();
      if (method == null) {
        return false;
      }
      final PsiClass aClass = method.getContainingClass();
      if (aClass == null) {
        return false;
      }
      final String name1 = aClass.getQualifiedName();
      return CommonClassNames.JAVA_LANG_STRING_BUFFER.equals(name1) ||
             CommonClassNames.JAVA_LANG_STRING_BUILDER.equals(name1);
    }

    @Override
    public void visitReferenceExpression(PsiReferenceExpression expression) {
      if (!myReplaceable || expression.getTextOffset() < myVariable.getTextOffset()) {
        return;
      }
      super.visitReferenceExpression(expression);
      final PsiExpression qualifier = expression.getQualifierExpression();
      if (qualifier != null) {
        return;
      }
      final PsiElement target = expression.resolve();
      if (!myVariable.equals(target)) {
        return;
      }
      if (myToStringFound) {
        myReplaceable = false;
        return;
      }
      final PsiElement element = PsiTreeUtil.getParentOfType(expression, PsiCodeBlock.class, PsiIfStatement.class, PsiLoopStatement.class);
      if (!myParent.equals(element)) {
        myReplaceable = false;
        return;
      }
      PsiElement parent = expression.getParent();
      while (true) {
        if (!(parent instanceof PsiReferenceExpression)) {
          myReplaceable = false;
          return;
        }
        final PsiElement grandParent = parent.getParent();
        if (!isAppendCall(grandParent)) {
          if (!isToStringCall(grandParent)) {
            myReplaceable = false;
            return;
          }
          myToStringFound = true;
          return;
        }
        if (myPossibleSideEffect) {
          myReplaceable = false;
          return;
        }
        parent = grandParent.getParent();
        if (parent instanceof PsiExpressionStatement) {
          return;
        }
      }
    }
  }
}
