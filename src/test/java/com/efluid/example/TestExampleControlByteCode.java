package com.efluid.example;

import com.efluid.tcbc.TestControleByteCode;
import com.efluid.tcbc.object.Classe;
import com.efluid.tcbc.object.Jar;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.implementation.FixedValue;

import java.io.File;
import java.io.IOException;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test an error usage case when a signature is modified by changing the return type from String to void.
 */
public class TestExampleControlByteCode extends TestControleByteCode {

  private static final String NAME_CLASS_CREATED_BY_BYTE_BUDDY = "ClassCreatedByByteBuddy";
  private static final String TARGET_TEST_CLASSES = "target/test-classes";

  @Override
  public void init() {
    super.init();
    changeTheReturnTypeFromStringToVoid();
  }

  @Override
  protected void isValid(int erreurs) {
    int nombreErreurs = getJarsTraites().stream().flatMap(jar -> jar.getClassesEnErreur().stream()).mapToInt(Classe::getNbErreurs).sum();
    assertThat(nombreErreurs).isEqualTo(1);
    Classe classInError = getJarsTraites().stream().filter(Jar::isErreur)
      .flatMap(jar -> jar.getClassesEnErreur().stream())
      .findFirst().orElseThrow(NoSuchElementException::new);
    assertThat(classInError.getNom()).contains(NAME_CLASS_CREATED_BY_BYTE_BUDDY);
  }

  private void changeTheReturnTypeFromStringToVoid() {
    try {
      new ByteBuddy()
        .subclass(TestExampleControlByteCode.class)
        .name(NAME_CLASS_CREATED_BY_BYTE_BUDDY)
        .defineMethod("test", Void.class, Visibility.PUBLIC)
        .intercept(FixedValue.nullValue())
        .make()
        .saveIn(new File(TARGET_TEST_CLASSES));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Override
  protected boolean isScanneRepertoireClasses() {
    return true;
  }

  /**
   * Utilisé par {@link TestExampleControlByteCode}
   */
  public String test() {
    return "ok";
  }
}
