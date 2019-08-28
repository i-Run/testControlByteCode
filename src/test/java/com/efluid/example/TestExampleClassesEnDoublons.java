package com.efluid.example;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Ignore;

import com.efluid.tcbc.ControleClasseEnDoublonTest;

public class TestExampleClassesEnDoublons extends ControleClasseEnDoublonTest {

  @Override
  protected void isValid(int erreurs) {
    assertThat(1).isEqualTo(erreurs);
    assertThat(classesEnDoublon.get(Ignore.class.getName())).isNotNull();
  }
}
