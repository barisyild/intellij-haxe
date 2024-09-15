/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2015 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2017-2018 Ilya Malanin
 * Copyright 2018-2020 Eric Bishton
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
package com.intellij.plugins.haxe.model.evaluator;

import com.intellij.openapi.diagnostic.LogLevel;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.util.RecursionGuard;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.plugins.haxe.ide.annotator.semantics.HaxeCallExpressionUtil;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.lang.psi.impl.AbstractHaxeNamedComponent;
import com.intellij.plugins.haxe.lang.psi.impl.HaxeObjectLiteralImpl;
import com.intellij.plugins.haxe.model.*;
import com.intellij.plugins.haxe.model.type.*;
import com.intellij.psi.*;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;


import  static com.intellij.plugins.haxe.model.evaluator.HaxeExpressionEvaluatorHandlers.*;
import static com.intellij.plugins.haxe.model.evaluator.HaxeExpressionEvaluatorHandlers.handleWithRecursionGuard;
import static com.intellij.plugins.haxe.model.evaluator.HaxeExpressionUsageUtil.findUsageAsParameterInFunctionCall;
import static com.intellij.plugins.haxe.model.evaluator.HaxeExpressionUsageUtil.searchReferencesForTypeParameters;
import static com.intellij.plugins.haxe.model.type.HaxeMacroTypeUtil.getTypeDefinition;

@CustomLog
public class HaxeExpressionEvaluator {
  static { log.setLevel(LogLevel.INFO); }


  @NotNull
  static public HaxeExpressionEvaluatorContext evaluate(PsiElement element, HaxeGenericResolver resolver) {
    ProgressIndicatorProvider.checkCanceled();
    HaxeExpressionEvaluatorContext context = new HaxeExpressionEvaluatorContext(element);
    context.result = handle(element, context, resolver);
    return context;
  }

  // evaluation of complex expressions can in some cases result in needing the type for a psiElement multiple times
  // untyped parameters and variables can cause a lot of unnecessary computation if we have to re-evaluate them
  // in order to avoid this we put any useful results in a thread-local map that we clear once we are done with the evaluation
  record CacheRecord(ResultHolder holder, String resolverAsString){}
  private static final ThreadLocal<Map<PsiElement, CacheRecord>> resultCache = ThreadLocal.withInitial(HashMap::new);
  private static final ThreadLocal<Map<PsiElement, AtomicInteger>> resultCacheHits = ThreadLocal.withInitial(HashMap::new);
  private static final ThreadLocal<Stack<PsiElement>> processingStack = ThreadLocal.withInitial(Stack::new);



  @NotNull
  static public HaxeExpressionEvaluatorContext evaluate(PsiElement element, HaxeExpressionEvaluatorContext context,
                                                        HaxeGenericResolver resolver) {
    try {
      processingStack.get().push(element);
      ProgressIndicatorProvider.checkCanceled();
      context.result = handle(element, context, resolver);
      return context;
    }
    finally {
      cleanUp();
    }
  }
  @NotNull
  static public HaxeExpressionEvaluatorContext evaluateWithRecursionGuard(PsiElement element, HaxeExpressionEvaluatorContext context,
                                                                          HaxeGenericResolver resolver) {
    try {
      processingStack.get().push(element);
      ResultHolder result = handleWithRecursionGuard(element, context, resolver);
      context.result = result != null ? result : createUnknown(element);
      return context;
    }
    finally {
      cleanUp();
    }
  }

  private static void cleanUp() {
    Stack<PsiElement> processing = processingStack.get();
    processing.pop();
    if (processing.isEmpty()) {
      //if (!resultCache.get().isEmpty()) {
      //  log.info(" cached references" + resultCache.get().size());
      //}
      resultCache.set(new HashMap<>());
    }
  }



  // keep package protected, don't use this one outside code running from evaluate()
  // if used outside  it can mess up the result cache
  @NotNull
  static ResultHolder handle(@NotNull final PsiElement element,
                                     final HaxeExpressionEvaluatorContext context,
                                     final HaxeGenericResolver resolver) {
    try {
      ProgressIndicatorProvider.checkCanceled();

        HaxeExpressionEvaluatorCacheService service = element.getProject().getService(HaxeExpressionEvaluatorCacheService.class);
        return service.handleWithResultCaching(element, context, resolver);
    }
    catch (NullPointerException e) {
      // Make sure that these get into the log, because the GeneralHighlightingPass swallows them.
      log.error("Error evaluating expression type for element " + element, e);
      throw e;
    }
    catch (ProcessCanceledException e) {
      // Don't log these, because they are common, but DON'T swallow them, either; it makes things unresponsive.
      throw e;
    }

    catch (Throwable t) {
      // XXX: Watch this.  If it happens a lot, then maybe we shouldn't log it unless in debug mode.
      //log.warn("Error evaluating expression type for element " + (null == element ? "<null>" : element.toString()), t);
      //throw t;
    }
    return createUnknown(element.getNode() != null ? element : context.root);
  }

  // if recursion guard is triggered value will be null
  // this is intentional as providing an unknown result instead can break monomorphs
  @Nullable
  static  ResultHolder _handle(final PsiElement element,
                                      final HaxeExpressionEvaluatorContext context,
                                      HaxeGenericResolver optionalResolver) {
    if (element == null) {
      return createUnknown(context.root);
    }
    HaxeGenericResolver resolver = optionalResolver != null ? optionalResolver: HaxeGenericResolverUtil.generateResolverFromScopeParents(element);

    if(log.isDebugEnabled())log.debug("Handling element: " + element);

    if (element instanceof PsiCodeBlock codeBlock) {
      return handleCodeBlock(context, resolver, codeBlock);
    }

    if (element instanceof HaxeImportAlias alias) {
      return handleImportAlias(element, alias);
    }
    // attempt at reducing unnecessary if checks by grouping psi types by their parent type
    if (element instanceof PsiStatement) {

      if (element instanceof HaxeReturnStatement returnStatement) {
        return handleReturnStatement(context, resolver, returnStatement);
      }

      if (element instanceof HaxeTryStatement tryStatement) {
        return handleTryStatement(context, resolver, tryStatement);
      }

      if (element instanceof HaxeCatchStatement catchStatement) {
        return handleCatchStatement(context, resolver, catchStatement);
      }

      if (element instanceof HaxeForStatement forStatement) {
        return handleForStatement(context, resolver, forStatement);
      }

      if (element instanceof HaxeSwitchStatement switchStatement) {
        return handleSwitchStatement(context, resolver, switchStatement);
      }

      if (element instanceof HaxeWhileStatement whileStatement) {
        return handleWhileStatement(context, resolver, whileStatement);
      }
      if (element instanceof HaxeMacroStatement macroStatement) {
        return handleMacroStatement(context, macroStatement, resolver);
      }

      if (element instanceof HaxeIfStatement ifStatement) {
        return handleIfStatement(context, resolver, ifStatement);
      }
    }


    if (element instanceof  HaxeNamedComponent) {

      // must be before  HaxeLocalVarDeclaration as HaxeSwitchCaseCaptureVar extends HaxeLocalVarDeclaration
      if (element instanceof HaxeSwitchCaseCaptureVar captureVar) {
        return handleSwitchCaseCaptureVar(resolver, captureVar);
      }

      if (element instanceof HaxeFieldDeclaration declaration) {
        return handleFieldDeclaration(context, resolver, declaration);
      }

      if (element instanceof HaxeLocalVarDeclaration varDeclaration) {
        return handleLocalVarDeclaration(context, resolver, varDeclaration);
      }

      if (element instanceof HaxeLocalFunctionDeclaration functionDeclaration) {
        return functionDeclaration.getModel().getFunctionType(resolver).createHolder();
      }

      if (element instanceof HaxeValueIterator valueIterator) {
        return handleValueIterator(context, resolver, valueIterator);
      }
      if (element instanceof HaxeIteratorkey || element instanceof HaxeIteratorValue) {
        return findIteratorType(element);
      }



      //NOTE: must be before HaxeParameter as HaxeRestParameter extends HaxeParameter
      if (element instanceof HaxeRestParameter restParameter) {
        return handleRestParameter(restParameter);
      }

      if (element instanceof HaxeParameter parameter) {
        boolean isUntyped = parameter.getTypeTag() == null && parameter.getVarInit() == null;
        if (isUntyped) {
          return handleParameter(context, resolver, parameter);
        }
        return handleParameter(context, resolver, parameter);
      }

    }

    if (element instanceof HaxeEnumExtractedValueReference extractedValue) {
      return handleEnumExtractedValue(extractedValue, resolver);
    }


    if (element instanceof HaxeReference) {

      if (element instanceof HaxeNewExpression expression) {
        ResultHolder holder = handleNewExpression(context, resolver, expression);
        return findMissingTypeParametersIfNecessary(context, resolver, expression, holder);
      }

      if (element instanceof HaxeThisExpression thisExpression) {
        return handleThisExpression(resolver, thisExpression);
      }

      if (element instanceof HaxeSuperExpression superExpression) {
        return handleSuperExpression(context, resolver, superExpression);
      }

      if (element instanceof HaxeCallExpression callExpression) {
        return handleCallExpression(context, resolver, callExpression);
      }

      if (element instanceof HaxeReferenceExpression referenceExpression) {
        return handleReferenceExpression(context, resolver, referenceExpression);
      }

      if (element instanceof HaxeCastExpression castExpression) {
        return handleCastExpression(castExpression);
      }

      if (element instanceof HaxeMapLiteral mapLiteral) {
        return handleMapLiteral(context, resolver, mapLiteral);
      }

      if (element instanceof HaxeArrayLiteral arrayLiteral) {
        return handleArrayLiteral(context, resolver, arrayLiteral);
      }

      if (element instanceof HaxeRegularExpressionLiteral regexLiteral) {
        return handleRegularExpressionLiteral(regexLiteral);
      }

      if (element instanceof HaxeStringLiteralExpression) {
        return handleStringLiteralExpression(element);
      }

      if (element instanceof HaxeTypeCheckExpr expr) {
        return handeTypeCheckExpr(element, expr, resolver);
      }
    }

    if (element instanceof HaxeLocalVarDeclarationList varDeclarationList) {
      return handleLocalVarDeclarationList(context, resolver, varDeclarationList);
    }

    if (element instanceof HaxeIterable iterable) {
      return handleIterable(context, resolver, iterable);
    }


    if (element instanceof  HaxeSwitchCaseBlock caseBlock) {
      return handleSwitchCaseBlock(context, resolver, caseBlock);
    }

    if (element instanceof HaxeIdentifier identifier) {
      return handleIdentifier(context, identifier);
    }

    if (element instanceof HaxeSpreadExpression spreadExpression) {
      return handleSpreadExpression(resolver, spreadExpression);
    }


    if (element instanceof HaxeAssignExpression assignExpression) {
      return handleAssignExpression(context, resolver, assignExpression);
    }

    if (element instanceof HaxeVarInit varInit) {
      return handleVarInit(context, resolver, varInit);
    }

    if(element instanceof HaxeObjectLiteralElement objectLiteralElement && objectLiteralElement.getExpression() != null) {
      return handle(objectLiteralElement.getExpression(), context, resolver);
    }

    if (element instanceof HaxeValueExpression valueExpression) {
      return handleValueExpression(context, resolver, valueExpression);
    }

    if (element instanceof HaxeLiteralExpression) {
      return handle(element.getFirstChild(), context, resolver);
    }

    if (element instanceof HaxeExpressionList expressionList) {
      return handleExpressionList(context, resolver, expressionList);
    }

    if (element instanceof HaxeFunctionLiteral function) {
      return handleFunctionLiteral(context, resolver, function);
    }

    if (element instanceof HaxeObjectLiteralImpl objectLiteral) {
      return SpecificHaxeAnonymousReference.withoutGenerics(new HaxeClassReference(objectLiteral.getModel(), objectLiteral)).createHolder();
    }

    if (element instanceof HaxePsiToken primitive) {
      return handlePrimitives(element, primitive);
    }


    if (element instanceof HaxeIteratorExpression iteratorExpression) {
      return handleIteratorExpression(context, resolver, iteratorExpression);
    }

    if (element instanceof HaxeArrayAccessExpression arrayAccessExpression) {
      return handleArrayAccessExpression(context, resolver, arrayAccessExpression);
    }


    if (element instanceof HaxeGuard haxeGuard) {  // Guard expression for if statement or switch case.
      return handleGuard(context, resolver, haxeGuard);
    }

    if (element instanceof HaxeParenthesizedExpression) {
      return handle(element.getChildren()[0], context, resolver);
    }

    if (element instanceof HaxeTernaryExpression ternaryExpression) {
      return handleTernaryExpression(context, resolver, ternaryExpression);
    }

    if (element instanceof HaxePrefixExpression prefixExpression) {
      return handlePrefixExpression(context, resolver, prefixExpression);
    }

    if (element instanceof HaxeIsTypeExpression) {
      return SpecificHaxeClassReference.primitive("Bool", element, null).createHolder();
    }

    //NOTE: check inheritance list before moving this one
    //check if common parent before checking all accepted variants (note should not include HaxeAssignExpression, HaxeIteratorExpression etc)
    if (element instanceof HaxeBinaryExpression expression) {
      return handleBinaryExpression(context, resolver, expression);
    }

    if (element instanceof HaxeTypeCheckExpr typeCheckExpr) {
      return handleTypeCheckExpr(context, resolver, typeCheckExpr);
    }

    if (element instanceof AbstractHaxeNamedComponent namedComponent) {
      return HaxeTypeResolver.getFieldOrMethodReturnType(namedComponent, resolver);
    }
    if (element instanceof HaxeMacroValueExpression  macroValueExpression) {
      if (macroValueExpression.getExpression() != null) {
        ResultHolder result = handle(macroValueExpression.getExpression(), context, resolver);
        if (!result.isUnknown()) {
          return HaxeMacroTypeUtil.getExprOf(element, result).createHolder();
        }else {
          return HaxeMacroTypeUtil.getExpr(element).createHolder();
        }
      } else if (macroValueExpression.getMacroTopLevelDeclaration() != null) {
        return getTypeDefinition(element).createHolder();
      }
    }
    if (element instanceof HaxeMacroClassReification classReification) {
      return getTypeDefinition(classReification).createHolder();
    }
    if (element instanceof HaxeMacroTypeReification typeReification) {
      return HaxeMacroTypeUtil.getComplexType(typeReification).createHolder();
    }

    if (element instanceof HaxeMacroValueReification valueReification) {
      return handle(valueReification.getExpression(), context, resolver);
    }

    if (element instanceof HaxeMacroArrayReification arrayReification) {
      ResultHolder resultHolder = handle(arrayReification.getExpression(), context, resolver);
      if (!resultHolder.isUnknown()) {
        return resultHolder;
      }

    }
    // TODO handle postfix (ex. myVar++ etc)


    if(log.isDebugEnabled()) log.debug("Unhandled " + element.getClass());
    return createUnknown(element);
  }

  private static @Nullable ResultHolder findMissingTypeParametersIfNecessary(HaxeExpressionEvaluatorContext context,
                                                                             HaxeGenericResolver resolver,
                                                                             HaxeNewExpression expression,
                                                                             ResultHolder typeHolder) {
    // if new expression is missing typeParameters try to resolve from usage
    HaxeType type = expression.getType();
    if (type.getTypeParam() == null && typeHolder.getClassType() != null && typeHolder.getClassType().getSpecifics().length > 0) {
      HaxePsiField fieldDeclaration = PsiTreeUtil.getParentOfType(expression, HaxePsiField.class);
      if (fieldDeclaration != null && fieldDeclaration.getTypeTag() == null) {
        SpecificHaxeClassReference classType = typeHolder.getClassType();
        // if class does not have any  generics there  no need to search for references
        if (classType != null  && classType.getSpecifics().length > 0) {
          HaxeComponentName componentName = fieldDeclaration.getComponentName();
          if(componentName != null) {
            ResultHolder searchResult = searchReferencesForTypeParameters(componentName, context, resolver, typeHolder);
            if (!searchResult.isUnknown()) {
              return searchResult;
            }
          }
        }
      }
    }
    return typeHolder;
  }

  private static ResultHolder handeTypeCheckExpr(PsiElement element, HaxeTypeCheckExpr expr, HaxeGenericResolver resolver) {
    if (expr.getTypeOrAnonymous() != null) {
      return HaxeTypeResolver.getTypeFromTypeOrAnonymous(expr.getTypeOrAnonymous(), resolver);
    }else if (expr.getFunctionType() != null) {
      return HaxeTypeResolver.getTypeFromFunctionType(expr.getFunctionType());
    }else {
      return createUnknown(element);
    }
  }

  private static ResultHolder handleMacroStatement(HaxeExpressionEvaluatorContext context, HaxeMacroStatement macroStatement, HaxeGenericResolver resolver) {
    @NotNull PsiElement[] children = macroStatement.getChildren();
    if (children.length > 0) {
      PsiElement child = children[0];
      ResultHolder result = handle(child, context, resolver);
    if (result.isUnknown() || result.isDynamic()) {
        // copy constant so we can try to improve unification (ex. null values are dynamic)
        return HaxeMacroTypeUtil.getExpr(child).withConstantValue(result.getType().getConstant()).createHolder();
      }else {
        return HaxeMacroTypeUtil.getExprOf(child, result).createHolder();
      }
    }
    // Unknown type, but since its an expression it should be an Expr right?
    return HaxeMacroTypeUtil.getExpr(macroStatement).createHolder();
  }


  private static boolean containsUnknowns(SpecificFunctionReference type) {
    if (type.getReturnType().isUnknown()) return true;
    List<SpecificFunctionReference.Argument> arguments = type.getArguments();
    for (SpecificFunctionReference.Argument argument : arguments) {
      if(argument.getType().isUnknown()) return true;
    }
    return false;
  }


  @NotNull
  public static ResultHolder findIteratorType(PsiElement iteratorElement) {
    HaxeForStatement forStatement = PsiTreeUtil.getParentOfType(iteratorElement, HaxeForStatement.class);
    HaxeGenericResolver forResolver = HaxeGenericResolverUtil.generateResolverFromScopeParents(forStatement);

    HaxeIterable iterable = forStatement.getIterable();
    var keyValueIteratorType = HaxeTypeResolver.getPsiElementType(iterable, iteratorElement, forResolver);

    var iteratorType = keyValueIteratorType.getClassType();
    if (iteratorType.isTypeDef()) {
      SpecificTypeReference type = iteratorType.fullyResolveTypeDefReference();
      if (type instanceof SpecificHaxeClassReference  classReference) {
        iteratorType = classReference;
      }
    }
    var iteratorTypeResolver = iteratorType.getGenericResolver();

    HaxeClassModel classModel = iteratorType.getHaxeClassModel();
    if (classModel == null) return createUnknown(iteratorElement);;
    // NOTE if "String" we need to add iterator types manually as  std string class does not have this method
    if (iteratorType.isString()) {
      if (iteratorElement instanceof HaxeIteratorkey ) {
        return SpecificTypeReference.getInt(iteratorElement).createHolder();
      }else  if (iteratorElement instanceof  HaxeIteratorValue){
        return SpecificTypeReference.getString(iteratorElement).createHolder();
      }
    }

    HaxeMethodModel iteratorReturnType = (HaxeMethodModel)classModel.getMember("next", iteratorTypeResolver);
    if (iteratorReturnType == null) return createUnknown(iteratorElement);;

    HaxeGenericResolver nextResolver = iteratorReturnType.getGenericResolver(null);
    nextResolver.addAll(iteratorTypeResolver);

    ResultHolder returnType = iteratorReturnType.getReturnType(nextResolver);
    SpecificHaxeClassReference type = returnType.getClassType();
    HaxeGenericResolver genericResolver = type.getGenericResolver();

    if (keyValueIteratorType.getClassType()!= null) {
      genericResolver.addAll(keyValueIteratorType.getClassType().getGenericResolver());
    }

    if (iteratorElement instanceof HaxeIteratorkey ) {
      return type.getHaxeClassModel().getMember("key", null).getResultType(genericResolver);
    }else  if (iteratorElement instanceof  HaxeIteratorValue){
      return type.getHaxeClassModel().getMember("value", null).getResultType(genericResolver);
    }
    return createUnknown(iteratorElement);
  }



  @NotNull
  public static ResultHolder searchReferencesForType(final HaxeComponentName componentName,
                                                     final HaxeExpressionEvaluatorContext context,
                                                     final HaxeGenericResolver resolver,
                                                     @Nullable final PsiElement searchScope
  ) {
  return searchReferencesForType(componentName, context, resolver,searchScope, null);
  }

  @NotNull
  public static ResultHolder searchReferencesForType(final HaxeComponentName componentName,
                                                     final HaxeExpressionEvaluatorContext context,
                                                     final HaxeGenericResolver resolver,
                                                     @Nullable final PsiElement searchScopePsi,
                                                     @Nullable final ResultHolder hint
  ) {
    List<PsiReference> references = referenceSearch(componentName, searchScopePsi);
    ResultHolder lastValue = null;
    int continueFrom = 0;
    for (int i = 0, size = references.size(); i < size; i++) {
      PsiReference reference = references.get(i);
      ResultHolder possibleType = checkSearchResult(context, resolver, reference, componentName, hint, i == 0);
      if (possibleType != null) {
        if (!possibleType.isUnknown()) {
          if (lastValue == null) {
            lastValue = possibleType;
            continueFrom = i+1;
          }
          if (!lastValue.isDynamic()) {
            // monomorphs should only use the first real value found (right?)
            //NOTE: don't use unify here (will break function type from  usage)
            if (!lastValue.isFunctionType()) break;
            boolean canAssign = lastValue.canAssign(possibleType);
            if (canAssign) {
              lastValue = possibleType;
              continueFrom = i+1;
            }
          }
        }
      }
    }
    if (lastValue != null && !lastValue.isUnknown()) {
      if(lastValue.containsTypeParameters()) {
        ResultHolder holder = searchReferencesForTypeParameters(componentName, context, resolver, lastValue, continueFrom);
        if (!holder.isUnknown()) return holder;
      }
      return lastValue;
    }

    return createUnknown(componentName);
  }
  @NotNull
  public static List<PsiReference> referenceSearch(final HaxeComponentName componentName, @Nullable final PsiElement searchScope) {
    PsiSearchHelper searchHelper = PsiSearchHelper.getInstance(componentName.getProject());
    SearchScope scope = searchScope != null ? new LocalSearchScope(searchScope) :  searchHelper.getCodeUsageScope(componentName);
    return referenceSearch(componentName, scope);
  }

  public static List<PsiReference> referenceSearch(final HaxeComponentName componentName, @NotNull final SearchScope searchScope) {
    int offset = componentName.getIdentifier().getTextRange().getEndOffset();
    return new ArrayList<>(ReferencesSearch.search(componentName, searchScope).findAll()).stream()
      .sorted((r1, r2) -> {
        int i1 = getDistance(r1, offset);
        int i2 = getDistance(r2, offset);
        return i1 - i2;
      }).toList();
  }

  private static final RecursionGuard<PsiElement>
    checkSearchResultRecursionGuard = RecursionManager.createGuard("EvaluatorCheckSearchResultRecursionGuard");

  @Nullable
  private static ResultHolder checkSearchResult(HaxeExpressionEvaluatorContext context, HaxeGenericResolver resolver, PsiReference reference,
                                                HaxeComponentName originalComponent,
                                                @Nullable ResultHolder hint, boolean firstReference) {

    if (originalComponent.getParent() == reference) return  null;
    if (reference instanceof HaxeExpression expression) {
      if (expression.getParent() instanceof HaxeAssignExpression assignExpression) {
        HaxeExpression rightExpression = assignExpression.getRightExpression();
        if(rightExpression != null) {
          ResultHolder result = handle(rightExpression, context, resolver);
          if (!result.isUnknown()) {
            return result;
          }
        }
        HaxeExpression leftExpression = assignExpression.getLeftExpression();
        if (leftExpression instanceof HaxeReferenceExpression referenceExpression) {
          PsiElement resolve = referenceExpression.resolve();
          if (resolve instanceof HaxePsiField psiField) {
            HaxeTypeTag tag = psiField.getTypeTag();
            if (tag != null) {
              ResultHolder holder = HaxeTypeResolver.getTypeFromTypeTag(tag, resolve);
              if (!holder.isUnknown()) {
                return holder;
              }
            }
          }
        }
      }
      if (expression.getParent() instanceof  HaxeObjectLiteralElement literalElement) {
        HaxeObjectLiteral objectLiteral = PsiTreeUtil.getParentOfType(literalElement, HaxeObjectLiteral.class);
        if(objectLiteral != null) {
          ResultHolder result = checkSearchResultRecursionGuard.computePreventingRecursion(objectLiteral, false, () -> {
            ResultHolder objectLiteralType = findObjectLiteralType(context, resolver, objectLiteral);
            if (objectLiteralType != null && !objectLiteralType.isUnknown()) {
              SpecificHaxeClassReference classType = objectLiteralType.getClassType();
              if (classType != null) {
                HaxeClassModel classModel = classType.getHaxeClassModel();
                if (classModel != null) {
                  HaxeGenericResolver genericResolver = classType.getGenericResolver();
                  HaxeBaseMemberModel member = classModel.getMember(literalElement.getName(), genericResolver);
                  if (member != null) {
                    ResultHolder type = member.getResultType(genericResolver);
                    if (type != null && !type.isUnknown()) {
                      return type;
                    }
                  }
                }
              }
            }
            return null;
          });
          if(result != null) return result;
        }
      }
    }
    if (reference instanceof HaxeReferenceExpression referenceExpression) {
      // reference is callExpression
      if (referenceExpression.getParent() instanceof HaxeCallExpression callExpression) {
        SpecificFunctionReference type = hint != null ? hint.getFunctionType() : null;
        if (type != null ) {
            HaxeCallExpressionUtil.CallExpressionValidation validation =
              HaxeCallExpressionUtil.checkFunctionCall(callExpression, type);


          Map<Integer, Integer> parameterToArgument = new HashMap<>();
          for(Map.Entry<Integer, Integer> entry : validation.getArgumentToParameterIndex().entrySet()){
            parameterToArgument.put(entry.getValue(), entry.getKey());
          }

          List<SpecificFunctionReference.Argument> newArgList = new ArrayList<>();

          Map<Integer, ResultHolder> argMap = validation.getArgumentIndexToType();

          List<SpecificFunctionReference.Argument> arguments = type.getArguments();
          for (SpecificFunctionReference.Argument argument : arguments) {
            int index = argument.getIndex();
            Integer argumentIndex = parameterToArgument.get(index);
            ResultHolder newValue = argMap.get(argumentIndex);
            // if not found use old value
            if (newValue == null) newValue = argument.getType();
            newArgList.add(new SpecificFunctionReference.Argument(argument.getIndex(), argument.isOptional(), argument.isRest(), newValue, argument.getName()));
          }

          return new SpecificFunctionReference(newArgList, type.returnValue ,type.functionType,  type.context).createHolder();
        }

      }
      // parameter in call expression
      if (referenceExpression.getParent().getParent() instanceof HaxeCallExpression callExpression) {
        if (callExpression.getExpression() instanceof HaxeReference callExpressionReference) {
          final PsiElement resolved = callExpressionReference.resolve();
          HaxeCallExpressionList list = callExpression.getExpressionList();
          // check if reference used as parameter
          ResultHolder paramType = findUsageAsParameterInFunctionCall(referenceExpression, callExpression, list, resolved);
          if (paramType != null) return paramType;
          // check if our reference is the callie
          final HaxeReference leftReference = PsiTreeUtil.getChildOfType(callExpression.getExpression(), HaxeReference.class);
          if (leftReference == reference) {
            if (resolved instanceof HaxeMethod method ) {
              HaxeCallExpressionUtil.CallExpressionValidation validation = HaxeCallExpressionUtil.checkMethodCall(callExpression, method, firstReference);
              ResultHolder hintResolved = validation.getResolver().resolve(hint);
              if (hintResolved != null && hintResolved.getType() != hintResolved.getType()) return hintResolved;
            }
          }
        }
      }
    }
    return null;
  }




  public static @Nullable ResultHolder findObjectLiteralType(HaxeExpressionEvaluatorContext context,
                                                  HaxeGenericResolver resolver,
                                                  HaxeObjectLiteral objectLiteral) {
    ResultHolder objectLiteralType = null;

    // we need to find  where the literal is used to find correct type
    if (objectLiteral.getParent() instanceof HaxeAssignExpression assignExpression) {
      objectLiteralType = handleWithRecursionGuard(assignExpression.getLeftExpression(), context, resolver);
    }
    if (objectLiteral.getParent() instanceof HaxeVarInit varInit) {
      HaxePsiField field = PsiTreeUtil.getParentOfType(varInit, HaxePsiField.class);
      if(field != null) {
        ResultHolder fieldType = HaxeTypeResolver.getTypeFromTypeTag(field.getTypeTag(), field);
        if(!fieldType.isUnknown()) return fieldType;
      }
    }
    if (objectLiteral.getParent().getParent() instanceof HaxeCallExpression callExpression) {
      if (callExpression.getExpression() instanceof HaxeReference callExpressionReference) {
        PsiElement resolve = callExpressionReference.resolve();
        ResultHolder holder = handleWithRecursionGuard(resolve, context, resolver);
        if (holder != null && holder.getFunctionType() != null) {
          SpecificFunctionReference functionCall = holder.getFunctionType();
          HaxeCallExpressionList list = callExpression.getExpressionList();
          objectLiteralType = findUsageAsParameterInFunctionCall(objectLiteral, callExpression, list, functionCall);
        }
      }
    }
    return objectLiteralType;
  }
}
