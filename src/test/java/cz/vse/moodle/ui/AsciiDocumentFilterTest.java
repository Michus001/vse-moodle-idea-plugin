package cz.vse.moodle.ui;

import org.junit.Test;

import javax.swing.text.AbstractDocument;
import javax.swing.text.PlainDocument;

import static org.junit.Assert.assertEquals;

public class AsciiDocumentFilterTest {
    @Test
    public void keepsOnlyPrintableAscii() {
        assertEquals("xnovj01@vse.cz, Jan Novk", AsciiDocumentFilter.keepAscii("xnovj01@vse.cz, Jan Novák"));
        assertEquals("ab", AsciiDocumentFilter.keepAscii("a\n\tb☃"));
    }

    @Test
    public void filtersTypedAndPastedText() throws Exception {
        AbstractDocument document = new PlainDocument();
        document.setDocumentFilter(new AsciiDocumentFilter());
        document.insertString(0, "Jiří ", null);
        document.replace(document.getLength(), 0, "x@vse.cz", null);
        assertEquals("Ji x@vse.cz", document.getText(0, document.getLength()));
    }
}
