/*
 *  Copyright (C) 2011-2017 Cojen.org
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.cojen.tupl;

import org.junit.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RecoverMappedTest extends RecoverTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(RecoverMappedTest.class.getName());
    }

    @Override
    protected void decorate(DatabaseConfig config) throws Exception {
        config.mapDataFiles(true);
    }

    @Test
    @Override
    public void largeUndo() throws Exception {
        if (TestUtils.is64bit()) {
            super.largeUndo();
        }
    }

    @Test
    @Override
    public void largeUndoExit() throws Exception {
        if (TestUtils.is64bit()) {
            super.largeUndoExit();
        }
    }

    @Test
    @Override
    public void largeRedo() throws Exception {
        if (TestUtils.is64bit()) {
            super.largeRedo();
        }
    }

    @Test
    @Override
    public void largeRedoExit() throws Exception {
        if (TestUtils.is64bit()) {
            super.largeRedoExit();
        }
    }
}
