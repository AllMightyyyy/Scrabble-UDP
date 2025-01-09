package scrabbleGame;

public class test {
    public static void main(String[] args) throws Exception {
        WordValidator validator = new WordValidator();
        System.out.println("Is 'apple' valid? " + validator.isValidWord("apple"));
        System.out.println("Is 'abcdxyz' valid? " + validator.isValidWord("abcdxyz"));
    }
}
