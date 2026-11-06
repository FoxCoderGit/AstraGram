package org.telegram.messenger;
import java.util.*;
public final class GhostProfileLookupParserTest {
 static int n;
 static void check(boolean b){n++;if(!b)throw new AssertionError("check "+n);}
 public static void main(String[]args){
  check(GhostProfileLookupParser.usernames(null).isEmpty());
  check(GhostProfileLookupParser.usernames("777000").isEmpty());
  check(GhostProfileLookupParser.usernames("@User_Name https://t.me/USER_name").equals(Arrays.asList("user_name")));
  check(GhostProfileLookupParser.usernames("https://telegram.me/AnotherUser?x=1").equals(Arrays.asList("anotheruser")));
  check(GhostProfileLookupParser.usernames("Username: Example_User").equals(Arrays.asList("example_user")));
  check(GhostProfileLookupParser.usernames("someone@example.org @usinfobot").isEmpty());
  check(GhostProfileLookupParser.usernames("@one1 @two2 @three3 @four4 @five5 @six6 @seven7").size()==6);
  check(GhostProfileLookupParser.usernames("https://t.me/share/url").isEmpty());
  check(GhostProfileLookupParser.usernames("@12test @abc").isEmpty());
  System.out.println("Profile lookup parser: "+n+" checks passed.");
 }
}
