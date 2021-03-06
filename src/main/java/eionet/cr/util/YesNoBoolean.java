/*
 * The contents of this file are subject to the Mozilla Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * The Original Code is Content Registry 2.0.
 *
 * The Initial Owner of the Original Code is European Environment
 * Agency.  Portions created by Tieto Eesti are Copyright
 * (C) European Environment Agency.  All Rights Reserved.
 *
 * Contributor(s):
 * Jaanus Heinlaid, Tieto Eesti
 */
package eionet.cr.util;

/**
 *
 * @author <a href="mailto:jaanus.heinlaid@tietoenator.com">Jaanus Heinlaid</a>
 *
 */
public enum YesNoBoolean {

    Y, N;

    /**
     *
     * @param b
     * @return
     */
    public static String format(boolean b) {
        return b ? Y.toString() : N.toString();
    }

    /**
     *
     * @param s
     * @return
     */
    public static boolean parse(String s) {

        if (s == null) {
            return false;
        } else if (s.equals(Y.toString())) {
            return true;
        } else if (s.equals(N.toString())) {
            return false;
        } else {
            throw new IllegalArgumentException(s);
        }
    }
}
