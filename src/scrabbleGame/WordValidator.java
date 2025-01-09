package scrabbleGame;

import net.sf.extjwnl.dictionary.Dictionary;
import net.sf.extjwnl.data.IndexWord;
import net.sf.extjwnl.data.POS;
import net.sf.extjwnl.JWNLException;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * A class to validate words using extJWNL + WordNet.
 */
public class WordValidator {

    private Dictionary dictionary;

    public WordValidator() throws JWNLException, IOException {
        // Initialize extJWNL with prop file
        dictionary = Dictionary.getInstance(new FileInputStream("wordnet_properties.xml"));
    }

    /**
     * Checks if the given word is a valid English word.
     * We'll try NOUN and VERB
     *
     * @param word The word to validate.
     * @return true if valid, false if not.
     */
    public boolean isValidWord(String word) {
        if (word == null || word.trim().isEmpty()) {
            return false;
        }
        String lower = word.toLowerCase();
        try {
            IndexWord indexNoun = dictionary.lookupIndexWord(POS.NOUN, lower);
            if (indexNoun != null) {
                return true;
            }
            IndexWord indexVerb = dictionary.lookupIndexWord(POS.VERB, lower);
            if (indexVerb != null) {
                return true;
            }
            // Can add checks for adjectives, acronyms, etc.
        } catch (JWNLException e) {
            e.printStackTrace();
        }
        return false;
    }
}
