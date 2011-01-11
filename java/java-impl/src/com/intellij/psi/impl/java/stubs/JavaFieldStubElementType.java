/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package com.intellij.psi.impl.java.stubs;

import com.intellij.lang.ASTNode;
import com.intellij.lang.LighterASTNode;
import com.intellij.lang.LighterAST;
import com.intellij.psi.*;
import com.intellij.psi.impl.cache.RecordUtil;
import com.intellij.psi.impl.cache.TypeInfo;
import com.intellij.psi.impl.compiled.ClsEnumConstantImpl;
import com.intellij.psi.impl.compiled.ClsFieldImpl;
import com.intellij.psi.impl.java.stubs.impl.PsiFieldStubImpl;
import com.intellij.psi.impl.java.stubs.index.JavaFieldNameIndex;
import com.intellij.psi.impl.source.PsiEnumConstantImpl;
import com.intellij.psi.impl.source.PsiFieldImpl;
import com.intellij.psi.impl.source.tree.ElementType;
import com.intellij.psi.impl.source.tree.JavaDocElementType;
import com.intellij.psi.impl.source.tree.JavaElementType;
import com.intellij.psi.impl.source.tree.LightTreeUtil;
import com.intellij.psi.impl.source.tree.java.EnumConstantElement;
import com.intellij.psi.stubs.IndexSink;
import com.intellij.psi.stubs.StubElement;
import com.intellij.psi.stubs.StubInputStream;
import com.intellij.psi.stubs.StubOutputStream;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.io.StringRef;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/*
 * @author max
 */
public abstract class JavaFieldStubElementType extends JavaStubElementType<PsiFieldStub, PsiField> {
  private static final int INITIALIZER_LENGTH_LIMIT = 1000;

  public JavaFieldStubElementType(@NotNull @NonNls final String id) {
    super(id);
  }

  public PsiField createPsi(final PsiFieldStub stub) {
    final boolean compiled = isCompiled(stub);
    if (compiled) {
      return stub.isEnumConstant() ? new ClsEnumConstantImpl(stub) : new ClsFieldImpl(stub);
    }
    else {
      return stub.isEnumConstant() ? new PsiEnumConstantImpl(stub) : new PsiFieldImpl(stub);
    }
  }

  public PsiField createPsi(final ASTNode node) {
    if (node instanceof EnumConstantElement) {
      return new PsiEnumConstantImpl(node);
    }
    else {
      return new PsiFieldImpl(node);
    }
  }

  public PsiFieldStub createStub(final PsiField psi, final StubElement parentStub) {
    final PsiExpression initializer = psi.getInitializer();
    final TypeInfo type = TypeInfo.create(psi.getTypeNoResolve(), psi.getTypeElement());
    final byte flags = PsiFieldStubImpl.packFlags(psi instanceof PsiEnumConstant,
                                                  RecordUtil.isDeprecatedByDocComment(psi),
                                                  RecordUtil.isDeprecatedByAnnotation(psi));
    return new PsiFieldStubImpl(parentStub, psi.getName(), type, encodeInitializer(initializer), flags);
  }

  @Override
  public PsiFieldStub createStub(final LighterAST tree, final LighterASTNode node, final StubElement parentStub) {
    final TypeInfo typeInfo = TypeInfo.create(tree, node, parentStub);

    boolean isDeprecatedByComment = false;
    boolean hasDeprecatedAnnotation = false;
    String name = null;
    String initializer = null;

    boolean expectingInit = false;
    for (final LighterASTNode child : tree.getChildren(node)) {
      final IElementType type = child.getTokenType();
      if (type == JavaDocElementType.DOC_COMMENT) {
        isDeprecatedByComment = RecordUtil.isDeprecatedByDocComment(tree, child);
      }
      else if (type == JavaElementType.MODIFIER_LIST) {
        hasDeprecatedAnnotation = RecordUtil.isDeprecatedByAnnotation(tree, child);
      }
      else if (type == JavaTokenType.IDENTIFIER) {
        name = RecordUtil.intern(tree.getCharTable(), child);
      }
      else if (type == JavaTokenType.EQ) {
        expectingInit = true;
      }
      else if (expectingInit && !ElementType.JAVA_COMMENT_OR_WHITESPACE_BIT_SET.contains(type) && type != JavaTokenType.SEMICOLON) {
        initializer = encodeInitializer(tree, child);
        break;
      }
    }

    final boolean isEnumConst = node.getTokenType() == JavaElementType.ENUM_CONSTANT;
    final byte flags = PsiFieldStubImpl.packFlags(isEnumConst, isDeprecatedByComment, hasDeprecatedAnnotation);

    return new PsiFieldStubImpl(parentStub, name, typeInfo, initializer, flags);
  }

  @Nullable
  private static String encodeInitializer(PsiExpression initializer) {
    if (initializer == null) return null;

    if (initializer instanceof PsiNewExpression || initializer instanceof PsiMethodCallExpression) {
      return PsiFieldStub.INITIALIZER_NOT_STORED;
    }

    if (initializer.getTextLength() > INITIALIZER_LENGTH_LIMIT) {
      return PsiFieldStub.INITIALIZER_TOO_LONG;
    }

    return initializer.getText();
  }

  private static String encodeInitializer(final LighterAST tree, final LighterASTNode initializer) {
    final IElementType type = initializer.getTokenType();
    if (type == JavaElementType.NEW_EXPRESSION || type == JavaElementType.METHOD_CALL_EXPRESSION) {
      return PsiFieldStub.INITIALIZER_NOT_STORED;
    }

    if (initializer.getEndOffset() - initializer.getStartOffset() > INITIALIZER_LENGTH_LIMIT) {
      return PsiFieldStub.INITIALIZER_TOO_LONG;
    }

    return LightTreeUtil.toFilteredString(tree, initializer, null);
  }

  public void serialize(final PsiFieldStub stub, final StubOutputStream dataStream) throws IOException {
    dataStream.writeName(stub.getName());
    TypeInfo.writeTYPE(dataStream, stub.getType(false));
    dataStream.writeName(stub.getInitializerText());
    dataStream.writeByte(((PsiFieldStubImpl)stub).getFlags());
  }

  public PsiFieldStub deserialize(final StubInputStream dataStream, final StubElement parentStub) throws IOException {
    final StringRef name = dataStream.readName();
    final TypeInfo type = TypeInfo.readTYPE(dataStream, parentStub);
    final StringRef initializerText = dataStream.readName();
    final byte flags = dataStream.readByte();
    return new PsiFieldStubImpl(parentStub, name, type, initializerText, flags);
  }

  public void indexStub(final PsiFieldStub stub, final IndexSink sink) {
    final String name = stub.getName();
    if (name != null) {
      sink.occurrence(JavaFieldNameIndex.KEY, name);
    }
  }

  public String getId(final PsiFieldStub stub) {
    final String name = stub.getName();
    if (name != null) return name;

    return super.getId(stub);
  }
}