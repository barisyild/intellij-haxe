package com.intellij.plugins.haxe.ide.inspections.intentions;

import com.intellij.codeInsight.CodeInsightUtilCore;
import com.intellij.codeInspection.util.IntentionName;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.util.HaxeElementGenerator;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

public class HaxeIntroduceFieldIntention extends HaxeUnresolvedSymbolIntentionBase<HaxeReferenceExpression> {

  protected final @NotNull SmartPsiElementPointer<HaxeClass> myPsiTargetPointer;

  private final String myModuleName;
  private final String myClassName;

  private final String expressionText;

  public HaxeIntroduceFieldIntention(@NotNull HaxeReferenceExpression expression,  @NotNull HaxeClass targetClass) {
    super(expression);
    @NotNull PsiElement[] children = expression.getChildren();
    expressionText = children[children.length-1].getText();
    myPsiTargetPointer = createPointer(targetClass);

    myModuleName = expression.getContainingFile().getName();
    myClassName = targetClass.getName();

  }


  @Override
  public @IntentionName @NotNull String getText() {
    String scope = myClassName != null ? myClassName : myModuleName;
    return "Create field '" + expressionText + "' in " + scope;
  }

  @Override
  protected String getPreviewName() {
    HaxeClass aClass = myPsiTargetPointer.getElement();
    return  aClass == null ? null : aClass.getQualifiedName();
  }


  protected PsiFile perform(@NotNull Project project, PsiElement element, @NotNull Editor editor, boolean preview) {
    if (element instanceof  HaxeReferenceExpression expression) {
      PsiFile containingFile = expression.getContainingFile();
      InsertInfo insertInfo = findInsertInfo(expression, preview);

      HaxeFieldDeclaration variableDeclaration = (HaxeFieldDeclaration)generateDeclaration(project).copy();

      PsiElement anchor = insertInfo.element();
      PsiUtilCore.ensureValid(anchor);

      if (insertInfo.isAfter()) {
        anchor.getParent().addAfter(variableDeclaration, anchor);
      } else {
        anchor.getParent().addBefore(variableDeclaration, anchor);
      }

      CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(containingFile);
      CodeStyleManager.getInstance(project)
        .reformatNewlyAddedElement(variableDeclaration.getParent().getNode(), variableDeclaration.getNode());

      return variableDeclaration.getContainingFile();
    }
    return element.getContainingFile();
  }


  private HaxeFieldDeclaration generateDeclaration(@NotNull Project project) {
    String text = "private" + (needsToBeStatic() ? " static" : "") + " var " + expressionText + ":" + guessElementType() + ";";
    return HaxeElementGenerator.createVarDeclaration(project, text);
  }




  private @NotNull InsertInfo findInsertInfo(@NotNull HaxeReferenceExpression expression, boolean readOnly) {
    HaxeModule myModule = PsiTreeUtil.getParentOfType(expression, HaxeModule.class);
    HaxeClass myClass = myPsiTargetPointer.getElement();
    if (myClass != null) {
      if (readOnly) myClass = copyFileAndReturnClonedPsiElement(myClass);
      return findInsertClass(expression, myClass);
    } else if (myModule != null) {
      List<? extends PsiElement> list = myModule.getModuleFieldDeclarationList();
      if (!list.isEmpty()) {
        return findLastFieldBeforeExpression(expression, list);
      }
      PsiElement sibling = myModule.getPrevSibling();
      if (sibling != null) return new InsertInfo (sibling, false);
    }
    return new InsertInfo (expression.getContainingFile().getFirstChild(), true);
  }

  private static @NotNull InsertInfo findInsertClass(PsiElement expression, HaxeClass myClass) {
    List<? extends PsiElement>  list = Arrays.stream(myClass.getFields()).toList();
    if (!list.isEmpty()) {
      return findLastFieldBeforeExpression(expression, list);
    }else {
      PsiElement brace = myClass.getLBrace();
      return brace != null ? new InsertInfo(brace, true) : new InsertInfo(myClass, false);
    }
  }

  private static InsertInfo findLastFieldBeforeExpression(PsiElement expression, List<? extends PsiElement> list) {
    PsiElement element = list.get(0);
    boolean isAfter = false;
    for (PsiElement next : list) {
      if (next.getTextRange().getEndOffset() < expression.getTextOffset()) {
        element = next;
        isAfter = true;
      } else {
        break;
      }
    }
    return new InsertInfo(element, isAfter);
  }
}
record InsertInfo (PsiElement element, boolean isAfter) {}
