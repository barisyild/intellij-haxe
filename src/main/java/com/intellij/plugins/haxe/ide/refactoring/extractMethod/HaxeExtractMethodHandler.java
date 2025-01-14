package com.intellij.plugins.haxe.ide.refactoring.extractMethod;

import com.intellij.codeInsight.CodeInsightUtilCore;
import com.intellij.codeInsight.template.impl.TemplateManagerImpl;
import com.intellij.codeInsight.template.impl.TemplateState;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.ide.refactoring.introduceVariable.HaxeIntroduceHandler;
import com.intellij.plugins.haxe.ide.refactoring.introduceVariable.HaxeIntroduceOperation;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.model.HaxeFieldModel;
import com.intellij.plugins.haxe.model.HaxeModel;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.introduce.inplace.InplaceVariableIntroducer;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;


public class HaxeExtractMethodHandler extends HaxeIntroduceHandler {
  public HaxeExtractMethodHandler() {
    super("Extract Method");
  }

  @Override
  public void invoke(@NotNull Project project, Editor editor, PsiFile file, DataContext dataContext) {
    performAction(new HaxeIntroduceOperation(project, editor, file, null, "Extract Method"));
  }

  @Override
  protected void performAction(HaxeIntroduceOperation operation) {
    final PsiFile file = operation.getFile();
    if (!CommonRefactoringUtil.checkReadOnlyStatus(file)) {
      return;
    }
    final Editor editor = operation.getEditor();
    final Project project = editor.getProject();
    if (editor.getSettings().isVariableInplaceRenameEnabled()) {
      final TemplateState templateState = TemplateManagerImpl.getTemplateState(operation.getEditor());
      if (templateState != null && !templateState.isFinished()) {
        return;
      }
    }


    PsiElement startElement = null;
    PsiElement stopElement = null;

    final SelectionModel selectionModel = editor.getSelectionModel();

    //  check selection direction and invert if right to left.
    int tmpSelectionStart = selectionModel.getSelectionStart();
    int tmpSelectionEnd = selectionModel.getSelectionEnd();
    if (tmpSelectionStart > tmpSelectionEnd) {
      int realEnd  = tmpSelectionStart;
      tmpSelectionStart = tmpSelectionEnd;
      tmpSelectionEnd = realEnd;
    }

    final int selectionStart = tmpSelectionStart;
    final int selectionEnd = tmpSelectionEnd;


    if (selectionModel.hasSelection()) {
      stopElement = file.findElementAt(selectionEnd);
      startElement = getOuterMostParentWithSameTextRangeStart(file.findElementAt(selectionStart), stopElement.getTextRange().getEndOffset());
    }

    startElement = findStartElement(startElement);
    stopElement = findEndElement(stopElement, selectionStart);


    if (startElement == null || stopElement == null) {
      showCannotPerformError(project, editor);
      return;
    }

    int startOffset = startElement.getTextRange().getStartOffset();
    int endOffset = stopElement.getTextRange().getEndOffset();
    if (!(startElement instanceof HaxePsiCompositeElement) ||  !(stopElement instanceof HaxePsiCompositeElement)) {
      throw  new UnableToExtractMethodException();
    }
    List<HaxePsiCompositeElement> expressions = collectExpressions((HaxePsiCompositeElement)startElement, (HaxePsiCompositeElement)stopElement);

    ExtractMethodBuilder methodBuilder = new ExtractMethodBuilder()
      .startOffset(startOffset)
      .endOffset(endOffset)
      .selectedText(selectionModel.getSelectedText())
      .expressions(expressions);

    try {

      methodBuilder.isStatic(needsStaticKeyword(startElement));
      methodBuilder.validateAndProcessExpressions();

      boolean partOfExpression = stopElement.getParent().getParent() instanceof  HaxeExpression;

      HaxeMethodDeclaration methodDeclaration = methodBuilder.buildExtractedMethod(project);
      PsiElement replacementExpression = methodBuilder.buildReplacementExpressionAndUpdateMethod(project, methodDeclaration);

      Document document = editor.getDocument();
      HaxePsiCompositeElement parentMethod = PsiTreeUtil.getParentOfType(startElement, HaxeMethodDeclaration.class);
      HaxePsiCompositeElement parentField = PsiTreeUtil.getParentOfType(startElement, HaxeFieldDeclaration.class);
      HaxePsiCompositeElement parentMember = parentMethod != null ? parentMethod : parentField;
      if (parentMember != null) {

        WriteCommandAction.writeCommandAction(project, parentMember.getContainingFile())
          .withGroupId("Extract method")
          .compute(() ->
                   {

                     parentMember.getParent().addAfter(methodDeclaration, parentMember);

                     CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(parentMember);

                     PsiDocumentManager instance = PsiDocumentManager.getInstance(project);
                     instance.doPostponedOperationsAndUnblockDocument(document);
                     instance.commitDocument(document);

                     int number = document.getLineNumber(selectionEnd);
                     String restOfLine = document.getText(new TextRange(selectionEnd, document.getLineEndOffset(number)));

                     String replacementText = replacementExpression.getText();
                     boolean selectionEndsWithSemi = selectionModel.getSelectedText().endsWith(";");
                     boolean selectionIsFollowedBySemi = restOfLine.trim().startsWith(";");
                     if (!selectionEndsWithSemi || selectionIsFollowedBySemi  || partOfExpression) {
                       document.replaceString(selectionStart, selectionEnd, replacementText);
                     } else {
                       document.replaceString(selectionStart, selectionEnd, replacementText + ";");
                     }


                     TextRange textRange = new TextRange(selectionStart, selectionStart + replacementExpression.getTextLength());
                     editor.getSelectionModel().setSelection(selectionStart, selectionStart + replacementExpression.getTextLength());

                     CodeStyleManager.getInstance(project).reformatText(file, List.of(textRange));
                     instance.commitDocument(document);
                     return null;
                   });

        startRenameMethod(operation, editor, file);
      }
    }catch (UnableToExtractMethodException extractMethodException) {
      // TODO better Error messages
      showCannotPerformError(project, editor);
    }

  }

  private static boolean needsStaticKeyword(PsiElement startElement) {
    HaxeMethod parentMethod = PsiTreeUtil.getParentOfType(startElement, HaxeMethodDeclaration.class);
    if (parentMethod != null) {
      return parentMethod.getModel().isStatic();
    }
    HaxeVarInit init = PsiTreeUtil.getParentOfType(startElement, HaxeVarInit.class);
    HaxeFieldDeclaration fieldDeclaration = PsiTreeUtil.getParentOfType(startElement, HaxeFieldDeclaration.class);
    if (init != null && fieldDeclaration != null) {
      HaxeModel model = fieldDeclaration.getModel();
      if( model instanceof HaxeFieldModel fieldModel) return fieldModel.isStatic();
    }
    return false;
  }

  private static void startRenameMethod(HaxeIntroduceOperation operation, Editor editor, PsiFile file) {
    int newSelectionEnd = editor.getSelectionModel().getSelectionEnd();

    PsiElement element = file.findElementAt(newSelectionEnd);
    while (element != null && !(element instanceof HaxePsiCompositeElement)) element = element.getPrevSibling();
    HaxeCallExpression newCallExpression = element instanceof HaxeCallExpression haxeCallExpression
                                           ? haxeCallExpression
                                           : PsiTreeUtil.getParentOfType(element, HaxeCallExpression.class);
    if (newCallExpression == null) newCallExpression = PsiTreeUtil.findChildOfType(element, HaxeCallExpression.class);
    if (newCallExpression != null && newCallExpression.getExpression() instanceof HaxeReferenceExpression referenceExpression) {
      operation.getEditor().getCaretModel().moveToOffset(newCallExpression.getExpression().getTextRange().getEndOffset());
      PsiElement resolve = referenceExpression.resolve();
      if (resolve instanceof HaxeMethodDeclaration method) {

        final InplaceVariableIntroducer<PsiElement> introducer =
          new HaxeInplaceVariableIntroducer(method.getComponentName(), operation,
                                            List.of(method.getComponentName()), "Introduce Method");

        introducer.performInplaceRefactoring(new LinkedHashSet<>(Optional.ofNullable(operation.getSuggestedNames()).orElse(List.of())));
      }
    }
  }

  @Nullable
  private static PsiElement findEndElement(PsiElement stopElement, int selectionStart) {
    while (stopElement != null && !(stopElement instanceof HaxePsiCompositeElement)) {
      PsiElement sibling = stopElement.getPrevSibling();
      if (sibling == null) break;
      // too wide, find child
      if (sibling.getTextRange().getStartOffset() < selectionStart) {
        sibling = sibling.getLastChild();
      }
      stopElement = sibling;
    }
    return stopElement;
  }

  @Nullable
  private static PsiElement findStartElement(PsiElement startElement) {
    while (startElement != null
           && !( startElement instanceof HaxeLocalVarDeclarationList)
           && !(startElement instanceof HaxeExpression)
           && !( startElement instanceof HaxePsiField)) {
      PsiElement sibling = startElement.getNextSibling();
      if (sibling == null) break;
      startElement = sibling;
    }
    return startElement;
  }

  private PsiElement getOuterMostParentWithSameTextRangeStart(PsiElement element, int maxOffset) {
    if (element == null) return null;
    TextRange range = element.getTextRange();
    int startOffset = range.getStartOffset();
    PsiElement outerMost = element;
    while (outerMost != null) {
      PsiElement parent = outerMost.getParent();
      if (parent != null
          && parent.getTextRange().getStartOffset() == startOffset
          && parent.getTextRange().getEndOffset() <= maxOffset) {
        outerMost = parent;
      }else {
        break;
      }
    }
    return outerMost;
  }




  private List<HaxePsiCompositeElement> collectExpressions(HaxePsiCompositeElement start, HaxePsiCompositeElement stop) {
    TextRange stopRange = stop.getTextRange();
    int endOffset = stopRange.getEndOffset();
    List<HaxePsiCompositeElement> expressions = new ArrayList<>();

    expressions.add(start);
    HaxeExpression next = PsiTreeUtil.getNextSiblingOfType(start, HaxeExpression.class);
    while (next != null) {
      if (next == stop) break;
      if (next.getTextRange().getEndOffset() > endOffset) break;// TODO error
      expressions.add(next);
      next = PsiTreeUtil.getNextSiblingOfType(next, HaxeExpression.class);
    }
    expressions.add(stop);

    return expressions;
  }

  @Override
  protected @Nullable PsiElement addDeclaration(@NotNull PsiElement expression,
                                                @NotNull PsiElement declaration,
                                                @NotNull HaxeIntroduceOperation operation) {
    return null;
  }
}
